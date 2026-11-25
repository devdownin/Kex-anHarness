// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors
package com.kex.agent.skills;

import java.util.List;

import com.kex.agent.memory.LearningEntry;

/**
 * La demande à rejouer, tirée de ce qui a fait naître la compétence. Sans elle, le réviseur
 * inventait une question : souvent une qui ne touchait aucun déclencheur, et le rejeu concluait à
 * l'absence d'effet d'une compétence qui n'avait même pas été indexée. La suggestion reste une
 * suggestion : la console la pré-remplit, le réviseur la corrige.
 *
 * @param request  {@code null} quand l'origine ne dit rien de rejouable (proposition manuelle)
 * @param triggers les déclencheurs de la candidate : une demande écrite à la main doit en toucher un
 */
public record ProbeSuggestion(String request, Source source, List<String> triggers) {
    static final int REQUEST_LIMIT = 4000;
    private static final String TASK_SOURCE = "task:";

    public enum Source {
        /** L'objectif de la tâche vérifiée dont la procédure est issue. */
        TASK,
        /** Les processus visés par les refus dont la règle est tirée. */
        REFUSALS,
        NONE
    }

    public ProbeSuggestion {
        triggers = triggers == null ? List.of() : List.copyOf(triggers);
    }

    public static ProbeSuggestion of(LearningEntry entry) {
        List<String> triggers = entry.triggers();
        // Une procédure de tâche commence par « # <objectif> » : le titre, lui, est tronqué à 160.
        if (entry.verification() != null && entry.verification().sourceId() != null
                && entry.verification().sourceId().startsWith(TASK_SOURCE) && entry.markdown().startsWith("# ")) {
            String objective = entry.markdown().lines().findFirst().orElse("").substring(2).strip();
            if (!objective.isEmpty()) return new ProbeSuggestion(bounded(objective), Source.TASK, triggers);
        }
        if (entry.title().startsWith(RefusalSkillProposer.TITLE_PREFIX) && !triggers.isEmpty()) {
            String capability = entry.title().substring(RefusalSkillProposer.TITLE_PREFIX.length()).strip();
            return new ProbeSuggestion(bounded(RefusalSkillProposer.probeRequest(capability, triggers)),
                    Source.REFUSALS, triggers);
        }
        return new ProbeSuggestion(null, Source.NONE, triggers);
    }

    private static String bounded(String value) {
        return value.length() <= REQUEST_LIMIT ? value : value.substring(0, REQUEST_LIMIT);
    }
}
