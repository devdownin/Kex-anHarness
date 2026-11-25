// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors
package com.kex.agent.skills;

import com.kex.agent.memory.LearningEntry;

/**
 * Le même texte a déjà été rejeté ou retiré par un humain. Le reproposer remettrait dans la file de
 * revue une question déjà tranchée, sans rien qui la distingue de la première fois.
 */
public final class SkillAlreadyJudgedException extends IllegalStateException {
    private final transient LearningEntry prior;

    public SkillAlreadyJudgedException(LearningEntry prior) {
        super("Procédure identique déjà " + ("RETIRED".equals(prior.status()) ? "retirée" : "rejetée")
                + (prior.reviewedBy() == null ? "" : " par " + prior.reviewedBy())
                + (prior.reviewedAt() == null ? "" : " le " + prior.reviewedAt())
                + (prior.reviewReason() == null || prior.reviewReason().isBlank() ? "" : " : " + prior.reviewReason()));
        this.prior = prior;
    }

    public LearningEntry prior() {
        return prior;
    }
}
