// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors
package com.kex.agent.memory;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Markdown content stays inert data until a named human approves a SKILL.
 *
 * @param scope    {@code null} hors compétence, et pour une compétence antérieure à la portée :
 *                 {@link #effectiveScope()} la lit alors comme {@link SkillScope#DOMAIN} sans
 *                 déclencheur, ce qui reproduit exactement l'injection d'avant
 * @param triggers termes normalisés qui rendent une compétence de domaine pertinente pour une demande
 * @param probes   les rejeux contrastifs, le plus récent en dernier
 */
public record LearningEntry(String id, String owner, String kind, String title, String markdown,
                            String evidence, String conversationId, Instant createdAt,
                            String status, String reviewedBy, Instant reviewedAt, String reviewReason,
                            LearningEvidence verification, SkillScope scope, List<String> triggers,
                            List<SkillProbe> probes) {

    /** Au-delà, le plus ancien rejeu tombe : une file de revue se lit, elle ne s'archive pas. */
    public static final int RETAINED_PROBES = 5;

    public LearningEntry {
        triggers = triggers == null ? List.of() : List.copyOf(triggers);
        probes = probes == null ? List.of() : List.copyOf(probes);
    }

    public LearningEntry(String id, String owner, String kind, String title, String markdown,
            String evidence, String conversationId, Instant createdAt, String status,
            String reviewedBy, Instant reviewedAt, String reviewReason, LearningEvidence verification) {
        this(id, owner, kind, title, markdown, evidence, conversationId, createdAt, status,
                reviewedBy, reviewedAt, reviewReason, verification, null, List.of(), List.of());
    }

    public LearningEntry(String id, String owner, String kind, String title, String markdown,
            String evidence, String conversationId, Instant createdAt, String status,
            String reviewedBy, Instant reviewedAt, String reviewReason) {
        this(id, owner, kind, title, markdown, evidence, conversationId, createdAt, status,
                reviewedBy, reviewedAt, reviewReason, null);
    }

    public LearningEntry reviewed(String status, String actor, Instant at, String reason) {
        return new LearningEntry(id, owner, kind, title, markdown, evidence, conversationId, createdAt,
                status, actor, at, reason, verification, scope, triggers, probes);
    }

    public LearningEntry withProbe(SkillProbe probe) {
        List<SkillProbe> next = new ArrayList<>(probes);
        next.add(probe);
        List<SkillProbe> retained = next.subList(Math.max(0, next.size() - RETAINED_PROBES), next.size());
        return new LearningEntry(id, owner, kind, title, markdown, evidence, conversationId, createdAt,
                status, reviewedBy, reviewedAt, reviewReason, verification, scope, triggers, retained);
    }

    public SkillScope effectiveScope() {
        return scope == null ? SkillScope.DOMAIN : scope;
    }
}
