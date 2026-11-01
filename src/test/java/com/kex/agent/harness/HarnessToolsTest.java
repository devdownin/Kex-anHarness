// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors
package com.kex.agent.harness;

import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kex.agent.agent.AgentExecution;
import com.kex.agent.agent.TaskToolPolicy;
import com.kex.agent.agent.TokenBudgetService;
import com.kex.agent.tools.ToolControlProperties;
import com.kex.agent.tools.ToolInvocationPolicy;
import com.kex.agent.tools.ToolSelectionService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.tool.ToolCallback;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class HarnessToolsTest {
    @TempDir Path directory;
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

    private HarnessTools harness(List<ToolCallback> tools, ChatModel model, ToolControlProperties controls) {
        var properties = HarnessFixtures.properties(directory.resolve("results.json").toString());
        return new HarnessTools(properties, new ToolSelectionService(controls, () -> tools.toArray(ToolCallback[]::new)),
                new ToolInvocationPolicy(controls, mapper),
                new StoredToolResults(properties, new FileToolResultRepository(mapper, Path.of(properties.results().storePath())), Clock.systemUTC()),
                HarnessFixtures.provider(ChatModel.class, model), mapper);
    }
    private static ToolCallback named(List<ToolCallback> tools, String name) {
        return tools.stream().filter(t -> t.getToolDefinition().name().equals(name)).findFirst().orElseThrow();
    }
    @Test
    void decouvre_puis_appelle_sans_reactiver_un_outil_interdit_et_recontrole_les_revocations() {
        var live = new ArrayList<ToolCallback>();
        AtomicInteger calls = new AtomicInteger();
        live.add(HarnessFixtures.tool("read_lag", (input, context) -> { calls.incrementAndGet(); return "lag=4"; }));
        live.add(HarnessFixtures.tool("restart", (input, context) -> { throw new AssertionError("mutation"); }));
        var harness = harness(live, HarnessFixtures.model(p -> { throw new AssertionError("modèle inutile"); }), ToolControlProperties.defaults());
        var context = HarnessFixtures.context("tenant", "conv");
        var tools = harness.prepare(List.of(), Set.of("read_lag"), context);
        var runtime = new ToolContext(context);
        assertThatThrownBy(() -> named(tools, "invoke_discovered_tool").call("{\"name\":\"read_lag\",\"arguments\":{}}", runtime))
                .isInstanceOf(SecurityException.class);
        String discovered = named(tools, "discover_tools").call("{\"query\":\"retard consommateur\"}", runtime);
        assertThat(discovered).contains("read_lag").doesNotContain("restart");
        assertThat(named(tools, "invoke_discovered_tool").call("{\"name\":\"read_lag\",\"arguments\":{}}", runtime))
                .contains("lag=4");
        assertThat(calls).hasValue(1);
        assertThatThrownBy(() -> named(tools, "invoke_discovered_tool").call("{\"name\":\"restart\",\"arguments\":{}}", runtime))
                .isInstanceOf(SecurityException.class);
        live.clear();
        assertThatThrownBy(() -> named(tools, "invoke_discovered_tool").call("{\"name\":\"read_lag\",\"arguments\":{}}", runtime))
                .isInstanceOf(SecurityException.class);
        assertThat(harness.prepare(List.of(), Set.of(), HarnessFixtures.context("tenant", "conv"))).isEmpty();
    }
    @Test
    void delegation_isole_le_contexte_partage_le_budget_et_reste_en_lecture_seule() {
        var controls = new ToolControlProperties(false, 12, 12000, 32000,
                Map.of("read_lag", new ToolControlProperties.Rule(false, true, Map.of(), Map.of())), List.of());
        AtomicReference<org.springframework.ai.chat.prompt.Prompt> seen = new AtomicReference<>();
        var model = HarnessFixtures.model(prompt -> {
            seen.set(prompt);
            return HarnessFixtures.answer("Observation datée : lag inconnu ; couverture partielle.");
        });
        var harness = harness(List.of(HarnessFixtures.tool("read_lag", (i, c) -> "4"),
                HarnessFixtures.tool("restart", (i, c) -> { throw new AssertionError(); })), model, controls);
        var context = HarnessFixtures.context("tenant", "conv");
        context.put(ContextSession.MEMORY, "HISTORIQUE_SECRET");
        context.put(ContextSession.KEY, new ContextSession());
        var tools = harness.prepare(List.of(), null, context);
        String report = named(tools, "delegate_diagnostic").call("{\"specialist\":\"kafka\",\"objective\":\"Vérifier le lag orders\"}",
                new ToolContext(context));
        assertThat(report).contains("UNVERIFIED", "couverture partielle");
        assertThat(seen.get().getInstructions().toString()).doesNotContain("HISTORIQUE_SECRET").contains("Vérifier le lag orders");
        var options = (ToolCallingChatOptions) seen.get().getOptions();
        assertThat(options.getToolCallbacks()).extracting(t -> t.getToolDefinition().name()).containsExactly("read_lag");
        assertThat(options.getToolContext().get(TokenBudgetService.TASK_KEY)).isEqualTo("root-budget");
        assertThat(options.getToolContext().get("kex.owner")).isEqualTo("tenant");
        assertThat(options.getToolContext()).doesNotContainKeys(ContextSession.MEMORY, ContextSession.KEY);
        assertThat(options.getToolContext().get(TaskToolPolicy.ALLOWED_TOOLS)).isEqualTo(Set.of("read_lag"));
        assertThat(options.getToolContext().get(AgentExecution.CONTEXT_KEY)).isNotSameAs(context.get(AgentExecution.CONTEXT_KEY));
    }
    @Test
    void le_specialiste_execute_la_vraie_boucle_doutils_et_ne_peut_pas_se_deleguer() {
        var controls = new ToolControlProperties(false, 12, 12000, 32000,
                Map.of("read_lag", new ToolControlProperties.Rule(false, true, Map.of(), Map.of())), List.of());
        AtomicInteger turns = new AtomicInteger();
        AtomicInteger measured = new AtomicInteger();
        var model = HarnessFixtures.model(prompt -> {
            assertThat(((ToolCallingChatOptions) prompt.getOptions()).getToolCallbacks())
                    .extracting(t -> t.getToolDefinition().name()).containsExactly("read_lag");
            if (turns.getAndIncrement() == 0) {
                return new org.springframework.ai.chat.model.ChatResponse(List.of(new org.springframework.ai.chat.model.Generation(
                        org.springframework.ai.chat.messages.AssistantMessage.builder().content("")
                                .toolCalls(List.of(new org.springframework.ai.chat.messages.AssistantMessage.ToolCall(
                                        "call-1", "function", "read_lag", "{}"))).build())));
            }
            assertThat(prompt.getInstructions().toString()).contains("lag=4");
            return HarnessFixtures.answer("Lag 4 observé par read_lag ; aucune action autorisée.");
        });
        var harness = harness(List.of(HarnessFixtures.tool("read_lag", (i, c) -> {
            measured.incrementAndGet();
            assertThat(c.getContext().get(TokenBudgetService.TASK_KEY)).isEqualTo("root-budget");
            return "lag=4";
        })), model, controls);
        var context = HarnessFixtures.context("tenant", "conv");
        var tools = harness.prepare(List.of(), null, context);
        assertThat(named(tools, "delegate_diagnostic").call("{\"specialist\":\"kafka\",\"objective\":\"Mesurer le lag\"}", new ToolContext(context)))
                .contains("UNVERIFIED", "Lag 4");
        assertThat(measured).hasValue(1);
        assertThat(turns).hasValue(2);
    }

    @Test
    void les_plafonds_de_delegation_et_doutils_empechent_les_boucles_non_bornees() {
        var controls = new ToolControlProperties(false, 12, 12000, 32000,
                Map.of("read_lag", new ToolControlProperties.Rule(false, true, Map.of(), Map.of())), List.of());
        AtomicInteger measured = new AtomicInteger();
        AtomicInteger sequence = new AtomicInteger();
        var model = HarnessFixtures.model(prompt -> new org.springframework.ai.chat.model.ChatResponse(List.of(
                new org.springframework.ai.chat.model.Generation(org.springframework.ai.chat.messages.AssistantMessage.builder()
                        .content("").toolCalls(List.of(new org.springframework.ai.chat.messages.AssistantMessage.ToolCall(
                                "call-" + sequence.incrementAndGet(), "function", "read_lag", "{}"))).build()))));
        var harness = harness(List.of(HarnessFixtures.tool("read_lag", (i, c) -> {
            measured.incrementAndGet(); return "lag inconnu";
        })), model, controls);
        var context = HarnessFixtures.context("tenant", "conv");
        var tools = harness.prepare(List.of(), null, context);
        var delegate = named(tools, "delegate_diagnostic");
        String arguments = "{\"specialist\":\"kafka\",\"objective\":\"Mesurer le lag\"}";
        assertThatThrownBy(() -> delegate.call(arguments, new ToolContext(context))).hasMessageContaining("Échec du diagnostic");
        assertThat(measured).hasValue(2);
        assertThatThrownBy(() -> delegate.call(arguments, new ToolContext(context))).hasMessageContaining("Échec du diagnostic");
        assertThat(measured).hasValue(4);
        assertThatThrownBy(() -> delegate.call(arguments, new ToolContext(context))).hasMessageContaining("Plafond de délégations");
        assertThat(measured).hasValue(4);
    }

    @Test
    void un_timeout_invalide_la_garde_enfant_meme_si_le_modele_ignore_linterruption() throws Exception {
        var properties = HarnessFixtures.properties(directory.resolve("results.json").toString());
        var original = properties.delegation();
        properties = new HarnessProperties(properties.results(), properties.compaction(),
                new HarnessProperties.Delegation(true, 1, 2, 2, 1000, java.time.Duration.ofMillis(200), original.specialists()),
                properties.discovery());
        var controls = new ToolControlProperties(false, 12, 12000, 32000,
                Map.of("read_lag", new ToolControlProperties.Rule(false, true, Map.of(), Map.of())), List.of());
        var release = new java.util.concurrent.CountDownLatch(1);
        var stopped = new java.util.concurrent.CountDownLatch(1);
        var blocked = new java.util.concurrent.atomic.AtomicBoolean();
        AtomicInteger effects = new AtomicInteger();
        var model = HarnessFixtures.model(prompt -> {
            while (release.getCount() != 0) {
                try { release.await(); }
                catch (InterruptedException ignored) { /* Fournisseur volontairement non interruptible. */ }
            }
            try {
                var options = (ToolCallingChatOptions) prompt.getOptions();
                options.getToolCallbacks().getFirst().call("{}", new ToolContext(options.getToolContext()));
            }
            catch (java.util.concurrent.CancellationException expected) { blocked.set(true); }
            finally { stopped.countDown(); }
            return HarnessFixtures.answer("trop tard");
        });
        var tools = List.of(HarnessFixtures.tool("read_lag", (i, c) -> { effects.incrementAndGet(); return "4"; }));
        var harness = new HarnessTools(properties, new ToolSelectionService(controls, () -> tools.toArray(ToolCallback[]::new)),
                new ToolInvocationPolicy(controls, mapper),
                new StoredToolResults(properties, new FileToolResultRepository(mapper, Path.of(properties.results().storePath())), Clock.systemUTC()),
                HarnessFixtures.provider(ChatModel.class, model), mapper);
        var context = HarnessFixtures.context("tenant", "conv");
        var delegate = named(harness.prepare(List.of(), null, context), "delegate_diagnostic");
        try {
            assertThatThrownBy(() -> delegate.call("{\"specialist\":\"kafka\",\"objective\":\"diagnostic\"}", new ToolContext(context)))
                    .hasMessageContaining("Diagnostic expiré");
        }
        finally { release.countDown(); }
        assertThat(stopped.await(2, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
        assertThat(blocked).isTrue();
        assertThat(effects).hasValue(0);
        assertThat(((AgentExecution) context.get(AgentExecution.CONTEXT_KEY))).satisfies(AgentExecution::ensureActive);
    }

    @Test
    void un_echange_annule_ne_lance_aucune_delegation_et_les_outils_locaux_exigent_un_contexte() {
        AtomicInteger calls = new AtomicInteger();
        var harness = harness(List.of(), HarnessFixtures.model(p -> { calls.incrementAndGet(); return HarnessFixtures.answer("ok"); }),
                ToolControlProperties.defaults());
        var context = HarnessFixtures.context("tenant", "conv");
        var tools = harness.prepare(List.of(), null, context);
        var delegate = named(tools, "delegate_diagnostic");
        ((AgentExecution) context.get(AgentExecution.CONTEXT_KEY)).cancel();
        assertThatThrownBy(() -> delegate.call("{\"specialist\":\"kafka\",\"objective\":\"diagnostic\"}", new ToolContext(context)))
                .isInstanceOf(java.util.concurrent.CancellationException.class);
        assertThatThrownBy(() -> delegate.call("{}")).isInstanceOf(SecurityException.class);
        assertThat(calls).hasValue(0);
    }
}
