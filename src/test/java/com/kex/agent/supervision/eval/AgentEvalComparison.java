// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors
package com.kex.agent.supervision.eval;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/** What a candidate changes against its baseline, score and cost side by side. */
public record AgentEvalComparison(String baselineModel, String baselineHarness, String candidateModel,
                                  String candidateHarness, Delta overall, Map<String, Delta> scenarios) {
    public AgentEvalComparison {
        scenarios = Map.copyOf(scenarios);
    }

    public record Delta(double passRate, Double toolCalls, Double inputTokens, Double outputTokens) {
        static Delta between(AgentEvalScoring.Rates before, AgentEvalScoring.Rates after) {
            return new Delta(after.passRate() - before.passRate(),
                    difference(before.meanToolCalls(), after.meanToolCalls()),
                    difference(before.meanInputTokens(), after.meanInputTokens()),
                    difference(before.meanOutputTokens(), after.meanOutputTokens()));
        }

        private static Double difference(Double before, Double after) {
            return before == null || after == null ? null : after - before;
        }
    }

    public void write(Path file) throws IOException {
        Files.createDirectories(file.toAbsolutePath().getParent());
        AgentEvalCorpus.JSON.writerWithDefaultPrettyPrinter().writeValue(file.toFile(), this);
    }
}
