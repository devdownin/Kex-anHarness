// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors
package com.kex.agent.mcp;

import java.util.List;

/**
 * Un prompt MCP rendu par son serveur : des <em>consignes</em>, jamais une exécution. Le serveur
 * qui le publie ne lance rien en le rendant, et Kex non plus : le texte part dans la conversation
 * comme une demande ordinaire, et chaque outil qu'il cite reste soumis à la politique des outils.
 *
 * @param messages les messages dans l'ordre du serveur ; un contenu non textuel est réduit à son type
 */
public record McpPromptRendering(String connection, String name, String description, List<Message> messages) {
    public McpPromptRendering {
        messages = List.copyOf(messages);
    }

    public record Message(String role, String text) {
    }
}
