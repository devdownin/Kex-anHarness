// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors
package com.kex.agent.memory;

import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Une même demande exécutée deux fois, au même moment, avec et sans la compétence candidate. Ce
 * que le rejeu établit, c'est un écart de comportement — d'autres outils, plus d'échecs, plus de
 * jetons —, jamais que la réponse candidate est juste : le modèle reste stochastique et ses biais
 * pèsent sur les deux exécutions. Aucun verdict n'est donc calculé ; l'humain qui tranche lit l'écart.
 *
 * @param toolsAdded   outils appelés avec la compétence et pas sans elle
 * @param toolsDropped outils appelés sans la compétence et plus avec elle
 */
public record SkillProbe(Instant observedAt, String actor, String request, Run reference, Run candidate,
                         List<String> toolsAdded, List<String> toolsDropped) {

    public SkillProbe {
        toolsAdded = toolsAdded == null ? List.of() : List.copyOf(toolsAdded);
        toolsDropped = toolsDropped == null ? List.of() : List.copyOf(toolsDropped);
    }

    public static SkillProbe of(Instant observedAt, String actor, String request, Run reference, Run candidate) {
        return new SkillProbe(observedAt, actor, request, reference, candidate,
                difference(candidate.tools(), reference.tools()), difference(reference.tools(), candidate.tools()));
    }

    private static List<String> difference(List<String> left, List<String> right) {
        Set<String> result = new LinkedHashSet<>(left);
        result.removeAll(Set.copyOf(right));
        return List.copyOf(result);
    }

    /**
     * Une exécution du rejeu. Les jetons restent nullables : un fournisseur qui ne les rend pas
     * n'a rien consommé de mesuré, il n'a pas consommé zéro.
     *
     * @param tools  les outils appelés, dans l'ordre et avec leurs répétitions
     * @param error  le type d'exception quand l'exécution a échoué ; jamais son message, qui
     *               recopie souvent le corps de réponse du fournisseur
     */
    public record Run(List<String> tools, int failedCalls, Integer inputTokens, Integer outputTokens,
                      String finishReason, String answer, String error) {
        public Run {
            tools = tools == null ? List.of() : List.copyOf(tools);
        }

        public static Run failed(String error) {
            return new Run(List.of(), 0, null, null, null, null, error);
        }
    }
}
