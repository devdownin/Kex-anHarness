// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors
package com.kex.agent.agent;

import java.util.List;

/**
 * @param tools        les outils réellement exécutés pour produire cette réponse, dans l'ordre
 * @param usage        les jetons consommés, {@code null} si le fournisseur n'en rend pas
 * @param finishReason pourquoi le modèle s'est arrêté, dans le vocabulaire du fournisseur
 *                     ({@code max_tokens} chez Anthropic, {@code length} chez OpenAI) : rendu tel
 *                     quel plutôt que normalisé, un mot inconnu ne devant pas devenir un verdict.
 *                     Sans lui, une réponse coupée au plafond de jetons se lit exactement comme une
 *                     réponse complète.
 * @param evidence     les chiffres de la réponse se retrouvent-ils dans ce que les outils ont rendu ?
 *                     {@code null} quand le contrôle n'a pas eu lieu (rejeu, sortie structurée) ;
 *                     une alerte, jamais un refus — voir {@link AnswerEvidence}
 */
public record AgentAnswer(String conversationId, String content, List<AgentEvent.ToolCall> tools,
                          AgentUsage usage, String finishReason, List<com.kex.agent.knowledge.KnowledgeSource> sources,
                          AnswerEvidence.Evidence evidence) {
    public AgentAnswer(String conversationId, String content, List<AgentEvent.ToolCall> tools,
            AgentUsage usage, String finishReason, List<com.kex.agent.knowledge.KnowledgeSource> sources) {
        this(conversationId, content, tools, usage, finishReason, sources, null);
    }
    public AgentAnswer(String conversationId, String content, List<AgentEvent.ToolCall> tools,
            AgentUsage usage, String finishReason) {
        this(conversationId, content, tools, usage, finishReason, List.of(), null);
    }
    public AgentAnswer { sources = List.copyOf(sources); }
}
