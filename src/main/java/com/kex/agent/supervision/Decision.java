// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors
package com.kex.agent.supervision;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Trace d'une décision, identifiée pour l'audit et le support. {@code correlationId} relie la
 * décision au cycle, à l'anomalie et à l'entrée d'audit qui en découlent.
 *
 * @param estimatedImpact impact estimé de l'action, affiché avant toute validation humaine
 * @param actionArguments arguments proposés par le modèle pour l'outil lié, déjà filtrés sur la liste
 *                        autorisée de la liaison. Ils font partie de ce que l'humain valide : figés à la
 *                        création de la décision, ils ne changent plus jusqu'à l'exécution.
 * @param policyVersion   version de politique en vigueur au moment de la décision : sans elle, une
 *                        décision relue six mois plus tard est inexplicable
 * @param resolvedBy      qui l'a tranchée. Porté ici et pas seulement dans l'audit : distinguer une
 *                        exécution autonome d'une validation humaine est ce qui permet de mesurer
 *                        l'agent, et rapprocher deux historiques bornés séparément serait fragile.
 */
public record Decision(String id, String cycleId, String anomalyId, String processId, String processName,
                       Capability capability, String objective, String context, String action,
                       List<Observation> observations, String estimatedImpact, double confidence,
                       DecisionStatus status, String result, String policyVersion, String correlationId,
                       String resolvedBy, Instant decidedAt, Instant resolvedAt, Instant expiresAt,
                       Map<String, Object> actionArguments) {

    public Decision {
        actionArguments = actionArguments == null ? Map.of() : Map.copyOf(actionArguments);
    }

    /** Décision sans arguments proposés : le cas de toute capacité qui n'en demande pas. */
    public Decision(String id, String cycleId, String anomalyId, String processId, String processName,
                    Capability capability, String objective, String context, String action,
                    List<Observation> observations, String estimatedImpact, double confidence,
                    DecisionStatus status, String result, String policyVersion, String correlationId,
                    String resolvedBy, Instant decidedAt, Instant resolvedAt, Instant expiresAt) {
        this(id, cycleId, anomalyId, processId, processName, capability, objective, context, action,
                observations, estimatedImpact, confidence, status, result, policyVersion, correlationId,
                resolvedBy, decidedAt, resolvedAt, expiresAt, Map.of());
    }


    /** Annote une décision sans la trancher : elle attend toujours, mais on sait pourquoi. */
    public Decision withResult(String note) {
        return new Decision(id, cycleId, anomalyId, processId, processName, capability, objective, context,
                action, observations, estimatedImpact, confidence, status, note, policyVersion,
                correlationId, resolvedBy, decidedAt, resolvedAt, expiresAt, actionArguments);
    }

    public Decision resolvedAs(DecisionStatus newStatus, String outcome, String actor, Instant at) {
        return new Decision(id, cycleId, anomalyId, processId, processName, capability, objective, context,
                action, observations, estimatedImpact, confidence, newStatus, outcome, policyVersion,
                correlationId, actor, decidedAt, at, expiresAt, actionArguments);
    }
}
