// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors
package com.kex.agent.memory;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonFormat;

/**
 * Une même demande exécutée plusieurs fois, au même moment, avec et sans la compétence candidate.
 * Ce que le rejeu établit, c'est un écart de comportement — d'autres outils, plus d'échecs, plus de
 * jetons —, jamais que la réponse candidate est juste : le modèle reste stochastique et ses biais
 * pèsent sur les deux côtés. Aucun verdict sur la réponse n'est donc calculé ; l'humain qui tranche
 * lit l'écart. {@link Reading} dit seulement si l'écart est lisible, ce qui se mesure.
 *
 * <p>Un rejeu antérieur aux répétitions portait une exécution par côté sous {@code reference} et
 * {@code candidate} : les alias le relisent comme une liste d'un élément.
 *
 * @param toolsAdded   outils appelés dans <em>chaque</em> exécution avec la compétence et dans aucune
 *                     sans elle : un écart qui ne tient qu'une fois sur trois n'est pas un écart stable
 * @param toolsDropped l'inverse
 * @param tools        pour chaque outil vu, dans combien d'exécutions de chaque côté il apparaît
 * @param reading      {@code null} pour un rejeu écrit avant la qualification : rien ne permet plus
 *                     de savoir si la candidate devait être lue
 */
public record SkillProbe(Instant observedAt, String actor, String request,
                         @JsonAlias("reference") @JsonFormat(with = JsonFormat.Feature.ACCEPT_SINGLE_VALUE_AS_ARRAY)
                         List<Run> references,
                         @JsonAlias("candidate") @JsonFormat(with = JsonFormat.Feature.ACCEPT_SINGLE_VALUE_AS_ARRAY)
                         List<Run> candidates,
                         List<String> toolsAdded, List<String> toolsDropped, List<ToolFrequency> tools,
                         Reading reading) {

    public SkillProbe {
        references = references == null ? List.of() : List.copyOf(references);
        candidates = candidates == null ? List.of() : List.copyOf(candidates);
        toolsAdded = toolsAdded == null ? List.of() : List.copyOf(toolsAdded);
        toolsDropped = toolsDropped == null ? List.of() : List.copyOf(toolsDropped);
        tools = tools == null ? List.of() : List.copyOf(tools);
    }

    /**
     * @param skillId     la candidate, pour compter les passes où {@code read_skill} l'a ouverte
     * @param readRequired faux quand la candidate est recopiée en entier dans le prompt (invariante) :
     *                    elle agit alors sans jamais être lue, et l'absence de lecture ne prouve rien
     */
    public static SkillProbe of(Instant observedAt, String actor, String request, List<Run> references,
                                List<Run> candidates, String skillId, boolean readRequired) {
        Set<String> seen = new LinkedHashSet<>();
        references.forEach(run -> seen.addAll(run.tools()));
        candidates.forEach(run -> seen.addAll(run.tools()));
        List<ToolFrequency> tools = seen.stream()
                .map(tool -> new ToolFrequency(tool, count(references, tool), count(candidates, tool))).toList();
        List<String> added = stable(tools,
                frequency -> frequency.inCandidates() == candidates.size() && frequency.inReferences() == 0);
        List<String> dropped = stable(tools,
                frequency -> frequency.inReferences() == references.size() && frequency.inCandidates() == 0);
        return new SkillProbe(observedAt, actor, request, references, candidates, added, dropped, tools,
                Reading.of(references, candidates, added, dropped, skillId, readRequired));
    }

    private static int count(List<Run> runs, String tool) {
        return (int) runs.stream().filter(run -> run.tools().contains(tool)).count();
    }

    private static List<String> stable(List<ToolFrequency> tools, Predicate<ToolFrequency> condition) {
        return tools.stream().filter(condition).map(ToolFrequency::tool).toList();
    }

    public record ToolFrequency(String tool, int inReferences, int inCandidates) { }

    public enum Outcome {
        /** Rien ne sépare l'écart du hasard : une seule passe, une candidate non lue, des échecs épars. */
        CONFOUNDED,
        /** Aucun signal mesuré ne diffère à chaque passe ; les réponses restent à lire. */
        NO_OBSERVED_EFFECT,
        /** Au moins un signal mesuré diffère à chaque passe, dans le même sens. */
        STABLE_DIFFERENCE
    }

    /**
     * Ce que le rejeu permet d'affirmer de l'écart, pas de la réponse. {@code CONFOUNDED} l'emporte :
     * un écart observé sur une passe, ou sans que la candidate ait été lue, s'attribuerait à la
     * procédure sans que rien ne le montre. Les jetons ne comptent pas : sans seuil fondé, toute
     * variation passerait pour un effet.
     *
     * @param reasons pourquoi, en clair, pour l'écran de revue
     */
    public record Reading(Outcome outcome, List<String> reasons) {
        public Reading {
            Objects.requireNonNull(outcome);
            reasons = reasons == null ? List.of() : List.copyOf(reasons);
        }

        static Reading of(List<Run> references, List<Run> candidates, List<String> added, List<String> dropped,
                          String skillId, boolean readRequired) {
            List<String> confounders = new ArrayList<>();
            if (references.size() < 2 || candidates.size() < 2) {
                confounders.add("Une seule passe d'un côté : un écart peut tenir au hasard d'un essai.");
            }
            if (readRequired) {
                long read = candidates.stream().filter(run -> run.skillsRead().contains(skillId)).count();
                if (read < candidates.size()) {
                    confounders.add("Compétence lue dans " + read + " passe(s) sur " + candidates.size()
                            + " : une passe qui ne l'a pas lue n'a pas pu en subir l'effet.");
                }
            }
            partialFailures("sans la compétence", references).ifPresent(confounders::add);
            partialFailures("avec la compétence", candidates).ifPresent(confounders::add);
            if (!confounders.isEmpty()) return new Reading(Outcome.CONFOUNDED, confounders);

            List<String> differences = new ArrayList<>();
            if (!added.isEmpty()) differences.add("Outils ajoutés à chaque passe : " + String.join(", ", added) + ".");
            if (!dropped.isEmpty()) differences.add("Outils abandonnés à chaque passe : " + String.join(", ", dropped) + ".");
            boolean referencesFail = references.stream().allMatch(run -> run.error() != null);
            boolean candidatesFail = candidates.stream().allMatch(run -> run.error() != null);
            if (referencesFail != candidatesFail) {
                differences.add(candidatesFail ? "Chaque passe échoue avec la compétence, aucune sans."
                        : "Chaque passe échoue sans la compétence, aucune avec.");
            }
            Set<String> referenceStops = stops(references);
            Set<String> candidateStops = stops(candidates);
            if (!referencesFail && !candidatesFail && !referenceStops.isEmpty() && !candidateStops.isEmpty()
                    && referenceStops.stream().noneMatch(candidateStops::contains)) {
                differences.add("Motif d'arrêt différent à chaque passe : " + String.join(", ", referenceStops)
                        + " sans, " + String.join(", ", candidateStops) + " avec.");
            }
            if (differences.isEmpty()) {
                return new Reading(Outcome.NO_OBSERVED_EFFECT, List.of("Mêmes outils et mêmes motifs d'arrêt de part "
                        + "et d'autre ; un effet sur la réponse elle-même reste à lire."));
            }
            return new Reading(Outcome.STABLE_DIFFERENCE, differences);
        }

        /** Des échecs sur une partie des passes seulement : le bruit l'emporte sur le signal. */
        private static Optional<String> partialFailures(String side, List<Run> runs) {
            long failed = runs.stream().filter(run -> run.error() != null).count();
            return failed > 0 && failed < runs.size()
                    ? Optional.of(failed + " passe(s) sur " + runs.size() + " en échec " + side + ".")
                    : Optional.empty();
        }

        /**
         * Vide dès qu'une passe n'a pas rapporté son motif : un motif absent n'est pas un motif, et
         * « différent à chaque passe » ne s'affirme pas sur les seules passes qui en ont un.
         */
        private static Set<String> stops(List<Run> runs) {
            if (runs.stream().anyMatch(run -> run.finishReason() == null)) return Set.of();
            Set<String> stops = new LinkedHashSet<>();
            runs.forEach(run -> stops.add(run.finishReason()));
            return stops;
        }
    }

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
