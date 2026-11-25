// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors
package com.kex.agent.supervision.eval;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

/** Versioned network fixtures and independently declared observable outcomes. */
public final class AgentEvalCorpus {
    public static final String VERSION = "kex-supervision-v1";
    static final ObjectMapper JSON = new ObjectMapper();

    private AgentEvalCorpus() {
    }

    public static List<Scenario> load() throws IOException {
        try (var input = AgentEvalCorpus.class.getResourceAsStream("/evals/agent-scenarios.json")) {
            if (input == null) throw new IOException("Missing agent evaluation corpus");
            List<Scenario> scenarios = JSON.readValue(input, new TypeReference<>() { });
            if (scenarios.isEmpty()) throw new IOException("Empty agent evaluation corpus");
            Set<String> ids = new java.util.HashSet<>();
            for (Scenario scenario : scenarios) {
                if (!ids.add(scenario.id()) || scenario.allowedStates().isEmpty()
                        || scenario.requiredTools().isEmpty() || scenario.tools().isEmpty()
                        || !scenario.responses().keySet().containsAll(scenario.requiredTools())) {
                    throw new IOException("Invalid evaluation scenario: " + scenario.id());
                }
                List<String> leaks = leaks(scenario);
                if (!leaks.isEmpty()) {
                    throw new IOException("Evaluation scenario " + scenario.id() + " leaks its expectation into "
                            + "what the model reads: " + String.join(" | ", leaks));
                }
            }
            return List.copyOf(scenarios);
        }
    }

    /** Fields that state what a trial must do; the model must never read one of them. */
    static final List<String> EXPECTATION_FIELDS = List.of("risk", "allowedStates", "allowedTools", "requiredTools",
            "completeCoverage", "requiresUnreachedOrders", "requiresAnomaly", "forbidsAnomaly", "requiredArguments");
    private static final int RISK_FRAGMENT_WORDS = 4;

    /**
     * Tripwire on the fixture boundary, after Harness²'s check that no grader verdict reaches model
     * context: the tool catalogue and tool results are all the model reads from a scenario, so none
     * of the expectation may appear there — not the scenario id, not an expectation field name, not
     * four consecutive words of the stated risk. A fixture that tells the model what is being graded
     * scores the corpus, not the agent. Text is folded to unaccented lowercase words so a fragment
     * cannot slip through on punctuation or an accent.
     */
    static List<String> leaks(Scenario scenario) {
        String visible;
        try {
            visible = JSON.writeValueAsString(Map.of("tools", scenario.tools(), "responses", scenario.responses()));
        }
        catch (com.fasterxml.jackson.core.JsonProcessingException ex) {
            return List.of("fixture is not serialisable: " + ex.getOriginalMessage());
        }
        List<String> leaks = new java.util.ArrayList<>();
        if (visible.contains(scenario.id())) leaks.add("scenario id '" + scenario.id() + "'");
        EXPECTATION_FIELDS.stream().filter(field -> visible.contains("\"" + field + "\""))
                .forEach(field -> leaks.add("expectation field '" + field + "'"));
        String folded = " " + String.join(" ", words(visible)) + " ";
        List<String> risk = words(scenario.risk() == null ? "" : scenario.risk());
        for (int start = 0; start + RISK_FRAGMENT_WORDS <= risk.size(); start++) {
            String fragment = String.join(" ", risk.subList(start, start + RISK_FRAGMENT_WORDS));
            if (folded.contains(" " + fragment + " ")) {
                leaks.add("risk fragment '" + fragment + "'");
                break;
            }
        }
        return leaks;
    }

    private static List<String> words(String text) {
        String folded = java.text.Normalizer.normalize(text, java.text.Normalizer.Form.NFKD)
                .replaceAll("\\p{M}", "").toLowerCase(java.util.Locale.ROOT);
        return java.util.Arrays.stream(folded.split("[^\\p{L}\\p{N}]+")).filter(word -> !word.isEmpty()).toList();
    }

    public record Scenario(String id, String risk, List<Map<String, Object>> tools,
                           Map<String, Map<String, Object>> responses, List<String> requiredTools,
                           List<String> allowedTools, List<String> allowedStates,
                           Boolean completeCoverage, boolean requiresUnreachedOrders,
                           boolean requiresAnomaly, boolean forbidsAnomaly,
                           Map<String, Object> requiredArguments) {
    }
}
