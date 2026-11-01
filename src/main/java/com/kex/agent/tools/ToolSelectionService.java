// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors
package com.kex.agent.tools;

import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import com.kex.agent.config.AgentToolRegistry;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/** La pertinence choisit uniquement parmi les outils déjà autorisés, jamais une permission. */
@Component
public class ToolSelectionService {

    private final ToolControlProperties properties;
    private final Supplier<ToolCallback[]> tools;
    private Map<String, List<String>> keywords = Map.of();

    @Autowired
    public ToolSelectionService(ToolControlProperties properties, AgentToolRegistry registry,
                                com.kex.agent.harness.HarnessProperties harness) {
        this(properties, registry::callbacks);
        this.keywords = harness.discovery().keywords();
    }

    public ToolSelectionService(ToolControlProperties properties, Supplier<ToolCallback[]> tools) {
        this.properties = properties;
        this.tools = tools;
    }

    public ToolSelectionService(ToolControlProperties properties, AgentToolRegistry registry) {
        this(properties, registry::callbacks);
    }

    public List<ToolCallback> select(String request, Set<String> allowedTools) {
        List<ToolCallback> authorized = authorized(allowedTools);
        if (!properties.selectionEnabled()) return authorized;
        return rank(request, authorized, properties.maxSelectedTools());
    }

    public List<ToolCallback> authorized(Set<String> allowedTools) {
        // Une liste vide signifie aucun outil. Ne pas découvrir les serveurs pour ce cas.
        if (allowedTools != null && allowedTools.isEmpty()) return List.of();
        return Arrays.stream(tools.get())
                .filter(tool -> allowedTools == null || allowedTools.contains(tool.getToolDefinition().name()))
                .toList();
    }

    public List<ToolCallback> rank(String request, List<ToolCallback> authorized, int limit) {
        Set<String> words = words(request);
        // Les descriptions et schémas MCP sont non fiables : ils ne participent pas au score.
        Comparator<ToolCallback> rank = Comparator
                .comparingLong((ToolCallback tool) -> score(tool.getToolDefinition().name(), words)).reversed()
                .thenComparing(tool -> tool.getToolDefinition().name());
        return authorized.stream().sorted(rank).limit(limit).toList();
    }

    private long score(String name, Set<String> request) {
        Set<String> terms = new java.util.HashSet<>(words(name));
        keywords.getOrDefault(name, List.of()).forEach(word -> terms.addAll(words(word)));
        return terms.stream().filter(request::contains).count();
    }

    private static Set<String> words(String text) {
        if (text == null) return Set.of();
        String normalized = java.text.Normalizer.normalize(text.toLowerCase(Locale.ROOT), java.text.Normalizer.Form.NFD)
                .replaceAll("\\p{M}+", "");
        Set<String> result = Arrays.stream(normalized.split("[^\\p{L}\\p{N}]+"))
                .filter(word -> word.length() > 1).collect(Collectors.toSet());
        Map<String, String> synonyms = Map.ofEntries(
                Map.entry("retard", "lag"), Map.entry("consommateur", "consumer"), Map.entry("consommateurs", "consumer"),
                Map.entry("groupe", "group"), Map.entry("groupes", "group"), Map.entry("sujet", "topic"),
                Map.entry("sujets", "topic"), Map.entry("prevision", "forecast"), Map.entry("previsions", "forecast"),
                Map.entry("documents", "document"), Map.entry("recherche", "search"), Map.entry("chercher", "search"),
                Map.entry("erreur", "error"), Map.entry("erreurs", "error"), Map.entry("sante", "health"));
        new java.util.ArrayList<>(result).forEach(word -> { if (synonyms.containsKey(word)) result.add(synonyms.get(word)); });
        return result;
    }
}
