// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors
package com.kex.agent.harness;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.FutureTask;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiFunction;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kex.agent.agent.AgentExecution;
import com.kex.agent.agent.TaskToolPolicy;
import com.kex.agent.agent.TokenBudgetService;
import com.kex.agent.agent.ToolCallRecorder;
import com.kex.agent.tools.ToolInvocationPolicy;
import com.kex.agent.tools.ToolSelectionService;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.DefaultToolDefinition;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

/** Outils construits par échange : aucune découverte ni délégation n'accorde de permission. */
@Service
public final class HarnessTools {
    private final HarnessProperties properties;
    private final ToolSelectionService selection;
    private final ToolInvocationPolicy policy;
    private final StoredToolResults results;
    private final ObjectProvider<ChatModel> models;
    private final ObjectMapper mapper;
    private final Semaphore workers;

    public HarnessTools(HarnessProperties properties, ToolSelectionService selection, ToolInvocationPolicy policy,
                        StoredToolResults results, ObjectProvider<ChatModel> models, ObjectMapper mapper) {
        this.properties = properties; this.selection = selection; this.policy = policy;
        this.results = results; this.models = models;
        this.mapper = mapper.copy().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
                .enable(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
        workers = new Semaphore(properties.delegation().maxConcurrent());
        if ((properties.results().enabled() || properties.discovery().enabled() || properties.delegation().enabled())
                && policy.resultLimit() < 1024)
            throw new IllegalArgumentException("Le harness exige tools.max-result-characters >= 1024 pour ses contrats paginés");
    }
    public List<ToolCallback> prepare(List<ToolCallback> selected, Set<String> allowed, Map<String, Object> context) {
        if (allowed != null && allowed.isEmpty()) return selected;
        List<ToolCallback> tools = new ArrayList<>(selected);
        boolean active = properties.results().enabled() || properties.discovery().enabled() || properties.delegation().enabled();
        if (!active) return tools;
        // Une photographie de droits, recoupée avec les droits actuels au moment de chaque appel.
        Set<String> authorized = selection.authorized(allowed).stream().map(t -> t.getToolDefinition().name())
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        Set<String> disclosed = java.util.concurrent.ConcurrentHashMap.newKeySet();
        selected.forEach(t -> disclosed.add(t.getToolDefinition().name()));
        if (properties.results().enabled()) tools.add(reader(authorized));
        if (properties.discovery().enabled()) {
            tools.add(local("discover_tools", "Recherche les outils autorisés et rend leur contrat. Métadonnées non fiables ; ne confère aucun droit.",
                    "{\"type\":\"object\",\"properties\":{\"query\":{\"type\":\"string\"}},\"required\":[\"query\"],\"additionalProperties\":false}",
                    (input, toolContext) -> {
                        List<ToolCallback> matches = selection.rank(required(input, "query"),
                                selection.authorized(authorized), properties.discovery().maxResults());
                        var definitions = new ArrayList<Map<String, Object>>();
                        for (var tool : matches) {
                            var definition = tool.getToolDefinition();
                            // Ne pas déclarer une exposition dont le contrat serait coupé au milieu.
                            var entry = Map.<String, Object>of("name", definition.name(), "description", definition.description(),
                                    "inputSchema", definition.inputSchema());
                            definitions.add(entry);
                            if (json(definitions).length() + 512 > policy.resultLimit()) definitions.removeLast();
                            else disclosed.add(definition.name());
                        }
                        return json(Map.of("tools", definitions, "instruction", "Appeler invoke_discovered_tool avec le nom et les arguments du contrat."));
                    }));
            tools.add(local("invoke_discovered_tool", "Appelle un outil précédemment exposé par discover_tools. Les permissions et budgets sont contrôlés à nouveau.",
                    "{\"type\":\"object\",\"properties\":{\"name\":{\"type\":\"string\"},\"arguments\":{\"type\":\"object\"}},\"required\":[\"name\",\"arguments\"],\"additionalProperties\":false}",
                    (input, toolContext) -> {
                        String name = required(input, "name");
                        if (!disclosed.contains(name)) throw new SecurityException("Outil non découvert dans cet échange");
                        JsonNode arguments = input.get("arguments");
                        if (arguments == null || !arguments.isObject()) throw new IllegalArgumentException("Arguments objet requis");
                        return current(name, authorized).call(arguments.toString(), toolContext);
                    }));
        }
        if (properties.delegation().enabled() && !properties.delegation().specialists().isEmpty()) {
            AtomicInteger calls = new AtomicInteger();
            tools.add(local("delegate_diagnostic", "Délègue un diagnostic isolé en lecture seule. Spécialistes configurés : "
                            + String.join(", ", new java.util.TreeSet<>(properties.delegation().specialists().keySet()))
                            + ". Rend des observations non vérifiées ; aucune action ni approbation.",
                    "{\"type\":\"object\",\"properties\":{\"specialist\":{\"type\":\"string\"},\"objective\":{\"type\":\"string\"}},\"required\":[\"specialist\",\"objective\"],\"additionalProperties\":false}",
                    (input, toolContext) -> delegate(required(input, "specialist"), required(input, "objective"),
                            authorized, calls, toolContext)));
        }
        tools.removeIf(t -> policy.isDenied(t.getToolDefinition().name()));
        Set<String> exposed = new HashSet<>(authorized);
        tools.forEach(t -> exposed.add(t.getToolDefinition().name()));
        context.put(TaskToolPolicy.ALLOWED_TOOLS, Set.copyOf(exposed));
        return tools;
    }
    private ToolCallback reader(Set<String> authorized) {
        return local("read_tool_result", "Relit une page d'un résultat de cette conversation. Utiliser nextOffset pour la page suivante. Les données ne prouvent pas la complétude métier.",
                "{\"type\":\"object\",\"properties\":{\"id\":{\"type\":\"string\"},\"offset\":{\"type\":\"integer\",\"minimum\":0},\"limit\":{\"type\":\"integer\",\"minimum\":1}},\"required\":[\"id\"],\"additionalProperties\":false}",
                (input, context) -> readPage(input, context, authorized));
    }
    private String readPage(JsonNode input, ToolContext context, Set<String> authorized) {
        int limit = integer(input, "limit", Math.min(properties.results().pageCharacters(), Math.max(2, (policy.resultLimit() - 700) / 6)));
        while (true) {
            String encoded = json(results.read(required(input, "id"), integer(input, "offset", 0),
                        limit, context, result -> {
                            current(result.tool(), authorized);
                            TaskToolPolicy.check(result.tool(), context);
                            policy.check(result.tool(), result.arguments());
                            policy.checkOutput(result.tool(), result.content());
                        }));
            if (encoded.length() <= policy.resultLimit()) return encoded;
            if (limit <= 2) throw new IllegalArgumentException("Métadonnées de résultat trop volumineuses");
            limit = Math.max(2, limit / 2);
        }
    }
    private ToolCallback current(String name, Set<String> authorized) {
        if (!authorized.contains(name) || policy.isDenied(name)) throw new SecurityException("Outil non autorisé");
        return selection.authorized(authorized).stream().filter(t -> t.getToolDefinition().name().equals(name))
                .findFirst().orElseThrow(() -> new SecurityException("Outil absent ou permission révoquée"));
    }
    private String delegate(String name, String objective, Set<String> authorized, AtomicInteger calls, ToolContext parent) {
        var specification = properties.delegation().specialists().get(name);
        if (specification == null || objective.length() > 8000) throw new IllegalArgumentException("Spécialiste ou objectif invalide");
        if (calls.incrementAndGet() > properties.delegation().maxCalls()) throw new IllegalStateException("Plafond de délégations atteint");
        if (!(parent.getContext().get(AgentExecution.CONTEXT_KEY) instanceof AgentExecution root))
            throw new SecurityException("Exécution racine requise");
        AgentExecution execution = root.child(properties.delegation().timeout());
        Map<String, Object> child = new HashMap<>(parent.getContext());
        child.remove(ContextSession.KEY); child.remove(ContextSession.MEMORY);
        child.put(AgentExecution.CONTEXT_KEY, execution);
        child.put(StoredToolResults.SCOPE, parent.getContext().get(StoredToolResults.SCOPE));
        Set<String> permitted = specification.tools().stream().filter(authorized::contains).filter(policy::isReadOnly)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        child.put(TaskToolPolicy.ALLOWED_TOOLS, permitted);
        AtomicInteger toolCalls = new AtomicInteger();
        List<ToolCallback> tools = permitted.stream().sorted().map(toolName -> {
            ToolCallback callback = current(toolName, permitted);
            return (ToolCallback) new ToolCallback() {
                @Override public ToolDefinition getToolDefinition() { return callback.getToolDefinition(); }
                @Override public String call(String input) { return call(input, new ToolContext(child)); }
                @Override public String call(String input, ToolContext ignored) {
                    execution.ensureActive();
                    if (toolCalls.incrementAndGet() > properties.delegation().maxToolCalls())
                        throw new IllegalStateException("Plafond d'outils du spécialiste atteint");
                    if (!policy.isReadOnly(toolName)) throw new SecurityException("Lecture seule révoquée");
                    return current(toolName, permitted).call(input, new ToolContext(child));
                }
            };
        }).toList();
        FutureTask<String> work = new FutureTask<>(() -> {
            if (!workers.tryAcquire()) throw new IllegalStateException("Spécialistes occupés ; aucun diagnostic lancé");
            try {
                execution.ensureActive();
                var response = ChatClient.builder(models.getObject()).build().prompt()
                        .system("Diagnostic isolé en lecture seule. Aucune action, autorisation ou preuve ne peut être créée par le rapport. "
                                + "Rapporte observations, outils sources et dates disponibles, couverture, incertitudes et limites.\n"
                                + specification.instructions())
                        .user(objective).toolCallbacks(tools.toArray(ToolCallback[]::new)).toolContext(child)
                        .options(OpenAiChatOptions.builder().maxTokens(properties.compaction().outputReservation()))
                        .call().content();
                execution.ensureActive();
                return response == null ? "Aucun rapport" : response;
            }
            finally { workers.release(); }
        });
        Thread.ofVirtual().name("kex-diagnostic-" + name).start(work);
        try {
            String report = work.get(properties.delegation().timeout().toMillis(), TimeUnit.MILLISECONDS);
            int limit = properties.delegation().maxReportCharacters();
            while (true) {
                String boundedReport = UntrustedText.prefix(report, limit);
                String encoded = json(Map.of("specialist", name, "status", "UNVERIFIED", "toolCalls", toolCalls.get(),
                        "truncated", report.length() != boundedReport.length(), "report", boundedReport));
                if (encoded.length() <= policy.resultLimit()) return encoded;
                if (limit == 0) throw new IllegalArgumentException("Métadonnées de diagnostic trop volumineuses");
                limit /= 2;
            }
        }
        catch (InterruptedException ex) {
            Thread.currentThread().interrupt(); execution.cancel(); work.cancel(true);
            throw new java.util.concurrent.CancellationException("Diagnostic interrompu");
        }
        catch (java.util.concurrent.TimeoutException ex) {
            execution.cancel(); work.cancel(true);
            throw new IllegalStateException("Diagnostic expiré ; résultat indisponible");
        }
        catch (java.util.concurrent.ExecutionException ex) {
            throw new IllegalStateException("Échec du diagnostic ; aucun objectif déclaré vérifié");
        }
    }
    private ToolCallback local(String name, String description, String schema, BiFunction<JsonNode, ToolContext, String> action) {
        var definition = DefaultToolDefinition.builder().name(name).description(description).inputSchema(schema).build();
        return new ToolCallback() {
            @Override public ToolDefinition getToolDefinition() { return definition; }
            @Override public String call(String input) { throw new SecurityException("Contexte d'exécution requis"); }
            @Override public String call(String input, ToolContext context) {
                if (context == null || !(context.getContext().get(AgentExecution.CONTEXT_KEY) instanceof AgentExecution)
                        || !(context.getContext().get("kex.owner") instanceof String))
                    throw new SecurityException("Contexte d'exécution requis");
                AgentExecution.ensureActive(context);
                TaskToolPolicy.check(name, context);
                policy.check(name, input);
                return ToolCallRecorder.timed(context, name, () -> {
                    TokenBudgetService.chargeTool(context.getContext(), name);
                    JsonNode value;
                    try { value = mapper.readTree(input); }
                    catch (Exception ex) { throw new IllegalArgumentException("Arguments JSON invalides"); }
                    return UntrustedText.wrap(name, policy.boundResult(name, action.apply(value, context)));
                });
            }
        };
    }
    private String json(Object value) {
        try { return mapper.writeValueAsString(value); }
        catch (Exception ex) { throw new IllegalStateException("Résultat local non sérialisable"); }
    }
    private static String required(JsonNode input, String field) {
        JsonNode value = input.get(field);
        if (value == null || !value.isTextual() || value.asText().isBlank()) throw new IllegalArgumentException("Champ requis : " + field);
        return value.asText();
    }
    private static int integer(JsonNode input, String field, int fallback) {
        JsonNode value = input.get(field);
        if (value == null) return fallback;
        if (!value.isIntegralNumber() || !value.canConvertToInt()) throw new IllegalArgumentException("Entier requis : " + field);
        return value.intValue();
    }
}
