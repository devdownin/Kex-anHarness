// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors
package com.kex.agent.agent;

/** Ce qui peut sortir d'un échange en flux : du texte, ou un outil qui vient de s'exécuter. */
public sealed interface AgentEvent {

    record Sources(java.util.List<com.kex.agent.knowledge.KnowledgeSource> sources) implements AgentEvent {
        public Sources { sources = java.util.List.copyOf(sources); }
    }

    record Token(String text) implements AgentEvent {
    }

    /**
     * Le constat sur les chiffres de la réponse, émis après le dernier jeton — il ne peut se faire
     * qu'une fois la réponse entière lue — et seulement quand il a quelque chose à dire.
     */
    record Evidence(AnswerEvidence.Evidence evidence) implements AgentEvent {
    }

    /**
     * Métadonnées de fin de flux : motif d'arrêt dans le vocabulaire du fournisseur et jetons
     * consommés, émis une fois le dernier fragment reçu afin qu'une réponse coupée au plafond
     * {@code max_tokens}/{@code length} ne se confonde pas avec une réponse complète.
     */
    record Metadata(String finishReason, AgentUsage usage) implements AgentEvent {
    }

    record ToolCall(String tool, long durationMillis, boolean failed, String observedAt, String result) implements AgentEvent {
        public ToolCall(String tool, long durationMillis, boolean failed) {
            this(tool, durationMillis, failed, null, null);
        }
    }
}
