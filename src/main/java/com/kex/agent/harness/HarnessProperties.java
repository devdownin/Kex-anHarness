// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors
package com.kex.agent.harness;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** Capacités optionnelles ; les droits restent définis par les politiques d'outils. */
@ConfigurationProperties("kex.agent.harness")
public record HarnessProperties(@DefaultValue Results results, @DefaultValue Compaction compaction,
                                @DefaultValue Delegation delegation, @DefaultValue Discovery discovery) {
    public record Results(@DefaultValue("false") boolean enabled,
                          @DefaultValue("${user.home}/.kex-agent-ai/tool-results.json") String storePath,
                          @DefaultValue("1h") Duration retention,
                          @DefaultValue("2000000") int maxResultCharacters,
                          @DefaultValue("10000000") int tenantCharacters,
                          @DefaultValue("100") int tenantResults,
                          @DefaultValue("4000") int pageCharacters) {
        public Results {
            if (retention == null || retention.isZero() || retention.isNegative() || maxResultCharacters < 128
                    || tenantCharacters < maxResultCharacters || tenantResults < 1 || pageCharacters < 1
                    || pageCharacters > 8000) throw new IllegalArgumentException("Limites de résultats invalides");
        }
    }
    public record Compaction(@DefaultValue("false") boolean enabled,
                             @DefaultValue("64000") int contextTokens,
                             @DefaultValue("0.75") double triggerFraction,
                             @DefaultValue("4096") int outputReservation,
                             @DefaultValue("6") int keepRecentMessages,
                             @DefaultValue("1024") int summaryTokens,
                             @DefaultValue("2000000") int maxHistoryCharacters) {
        public Compaction {
            if (contextTokens < 1024 || !(triggerFraction > 0 && triggerFraction < 1)
                    || outputReservation < 1 || outputReservation >= contextTokens || keepRecentMessages < 2
                    || summaryTokens < 128 || summaryTokens >= contextTokens || maxHistoryCharacters < contextTokens)
                throw new IllegalArgumentException("Limites de compaction invalides");
        }
    }
    public record Delegation(@DefaultValue("false") boolean enabled,
                             @DefaultValue("2") int maxConcurrent,
                             @DefaultValue("3") int maxCalls,
                             @DefaultValue("8") int maxToolCalls,
                             @DefaultValue("4000") int maxReportCharacters,
                             @DefaultValue("30s") Duration timeout,
                             @DefaultValue Map<String, Specialist> specialists) {
        public Delegation {
            specialists = specialists == null ? Map.of() : Map.copyOf(specialists);
            if (maxConcurrent < 1 || maxCalls < 1 || maxToolCalls < 1 || maxReportCharacters < 256
                    || timeout == null || timeout.isZero() || timeout.isNegative())
                throw new IllegalArgumentException("Limites de délégation invalides");
        }
    }
    public record Specialist(String instructions, Set<String> tools) {
        public Specialist {
            tools = tools == null ? Set.of() : Set.copyOf(tools);
            if (instructions == null || instructions.isBlank() || instructions.length() > 8000)
                throw new IllegalArgumentException("Instructions de spécialiste requises et bornées");
        }
    }
    public record Discovery(@DefaultValue("false") boolean enabled,
                            @DefaultValue("8") int maxResults,
                            @DefaultValue Map<String, List<String>> keywords) {
        public Discovery {
            keywords = keywords == null ? Map.of() : keywords.entrySet().stream().collect(
                    java.util.stream.Collectors.toUnmodifiableMap(Map.Entry::getKey, e -> List.copyOf(e.getValue())));
            if (maxResults < 1 || maxResults > 32) throw new IllegalArgumentException("Limite de découverte invalide");
        }
    }
}
