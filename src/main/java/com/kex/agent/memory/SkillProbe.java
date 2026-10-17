// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors
package com.kex.agent.memory;

import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonFormat;

/**
 * Une même demande exécutée plusieurs fois, au même moment, avec et sans la compétence candidate.
 * Ce que le rejeu établit, c'est un écart de comportement — d'autres outils, plus d'échecs, plus de
 * jetons —, jamais que la réponse candidate est juste : le modèle reste stochastique et ses biais
 * pèsent sur les deux côtés. Aucun verdict n'est donc calculé ; l'humain qui tranche lit l'écart.
 *
 * <p>Un rejeu antérieur aux répétitions portait une exécution par côté sous {@code reference} et
 * {@code candidate} : les alias le relisent comme une liste d'un élément.
 *
 * @param toolsAdded   outils appelés dans <em>chaque</em> exécution avec la compétence et dans aucune
 *                     sans elle : un écart qui ne tient qu'une fois sur trois n'est pas un écart stable
 * @param toolsDropped l'inverse
 * @param tools        pour chaque outil vu, dans combien d'exécutions de chaque côté il apparaît
 */
public record SkillProbe(Instant observedAt, String actor, String request,
                         @JsonAlias("reference") @JsonFormat(with = JsonFormat.Feature.ACCEPT_SINGLE_VALUE_AS_ARRAY)
                         List<Run> references,
                         @JsonAlias("candidate") @JsonFormat(with = JsonFormat.Feature.ACCEPT_SINGLE_VALUE_AS_ARRAY)
                         List<Run> candidates,
                         List<String> toolsAdded, List<String> toolsDropped, List<ToolFrequency> tools) {

    public SkillProbe {
        references = references == null ? List.of() : List.copyOf(references);
        candidates = candidates == null ? List.of() : List.copyOf(candidates);
        toolsAdded = toolsAdded == null ? List.of() : List.copyOf(toolsAdded);
        toolsDropped = toolsDropped == null ? List.of() : List.copyOf(toolsDropped);
        tools = tools == null ? List.of() : List.copyOf(tools);
    }

    public static SkillProbe of(Instant observedAt, String actor, String request, List<Run> references,
                                List<Run> candidates) {
        Set<String> seen = new LinkedHashSet<>();
        references.forEach(run -> seen.addAll(run.tools()));
        candidates.forEach(run -> seen.addAll(run.tools()));
        List<ToolFrequency> tools = seen.stream()
                .map(tool -> new ToolFrequency(tool, count(references, tool), count(candidates, tool))).toList();
        return new SkillProbe(observedAt, actor, request, references, candidates,
                stable(tools, frequency -> frequency.inCandidates() == candidates.size() && frequency.inReferences() == 0),
                stable(tools, frequency -> frequency.inReferences() == references.size() && frequency.inCandidates() == 0),
                tools);
    }

    private static int count(List<Run> runs, String tool) {
        return (int) runs.stream().filter(run -> run.tools().contains(tool)).count();
    }

    private static List<String> stable(List<ToolFrequency> tools, Predicate<ToolFrequency> condition) {
        return tools.stream().filter(condition).map(ToolFrequency::tool).toList();
    }

    public record ToolFrequency(String tool, int inReferences, int inCandidates) { }

    /**
     * Une exécution du rejeu. Les jetons restent nullables : un fournisseur qui ne les rend pas
     * n'a rien consommé de mesuré, il n'a pas consommé zéro.
     *
     * @param tools      les outils appelés, dans l'ordre et avec leurs répétitions
     * @param skillsRead les compétences lues par {@code read_skill} : une candidate jamais lue n'a
     *                   pas pu produire l'écart observé de ce côté
     * @param error      le type d'exception quand l'exécution a échoué ; jamais son message, qui
     *                   recopie souvent le corps de réponse du fournisseur
     */
    public record Run(List<String> tools, int failedCalls, Integer inputTokens, Integer outputTokens,
                      String finishReason, String answer, String error, List<String> skillsRead) {
        public Run {
            tools = tools == null ? List.of() : List.copyOf(tools);
            skillsRead = skillsRead == null ? List.of() : List.copyOf(skillsRead);
        }

        public static Run failed(String error) {
            return new Run(List.of(), 0, null, null, null, null, error, List.of());
        }
    }
}
