// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors
package com.kex.agent.config;

import java.util.List;

/**
 * La configuration du modèle telle qu'elle est réellement appliquée, pas telle qu'elle est écrite
 * dans un fichier : c'est l'{@code Environment} résolu qui est lu, variables d'environnement et
 * profils compris. Un écran qui affiche le contenu d'un YAML ne dit rien de ce qui tourne.
 *
 * <p>Aucune clé n'y figure, jamais — seulement sa présence. La route est derrière le bearer de
 * l'API, mais un secret rendu à un écran finit dans une capture d'écran ou un ticket.
 *
 * @param label            nom lisible du fournisseur effectif — « OpenRouter » plutôt que
 *                         « openai », que rien ne distinguerait d'un appel direct à OpenAI
 * @param baseUrl          {@code null} quand l'appel part directement chez le fournisseur
 * @param gateway          un point d'accès compatible OpenAI autre qu'OpenAI lui-même : la console y
 *                         lit un catalogue de modèles ({@code /models}), qu'il soit hébergé ou local
 * @param local            ce point d'accès est sur cette machine ou ce réseau (boucle locale, adresse
 *                         privée, service Compose) : rien n'y transite par un tiers. Séparé de
 *                         {@code gateway}, qui disait les deux à la fois — un serveur local se voyait
 *                         ou annoncé comme un tiers, ou privé de son catalogue
 * @param apiKeyPresent    {@code null} quand le fournisseur n'est pas connu d'ici : un {@code false}
 *                         affirmerait « pas de clé » là où la phrase vraie est « pas su regarder »
 * @param apiKeyVariable   la variable d'environnement à poser, pas la propriété Spring : c'est
 *                         elle qu'on écrit dans un compose ou un shell
 * @param warnings         ce que cette configuration coûte ou risque, en clair
 */
public record LlmView(String provider, String label, String model, String baseUrl, boolean gateway,
                      boolean local, Boolean apiKeyPresent, String apiKeyVariable, Integer maxTokens,
                      Double temperature, Integer maxToolCalls, String onToolLimitExceeded,
                      String requestTimeout, int maxHistoryMessages, boolean logInteractions,
                      String embeddingProvider, boolean knowledgeEnabled, String systemPrompt,
                      List<String> warnings) {
}
