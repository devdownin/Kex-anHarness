// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors
package com.kex.agent.mcp;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import io.modelcontextprotocol.spec.McpSchema;
import org.springframework.ai.mcp.DefaultMcpToolNamePrefixGenerator;
import org.springframework.ai.mcp.McpConnectionInfo;
import org.springframework.ai.mcp.McpToolNamePrefixGenerator;

/**
 * Nomme les outils comme Spring AI le fait, et retient de quelle connexion chacun vient.
 *
 * <p>{@code SyncMcpToolCallback} n'expose pas la connexion dont il sort, et le chemin piloté par
 * le modèle ne pouvait donc protéger tous les serveurs que par un seul disjoncteur : un serveur
 * en panne coupait les outils des autres. Le générateur de noms, lui, reçoit la connexion — le
 * client MCP s'annonce {@code <nom du client> - <clé de connexion>} — au moment où il baptise
 * chaque outil. Il suffit de la noter au passage ; les noms, eux, ne changent pas d'une lettre, car
 * les règles d'outils, les listes autorisées des tâches et les compétences s'y réfèrent.
 */
public class ConnectionTrackingToolNames implements McpToolNamePrefixGenerator {

    /** Séparateur posé par l'autoconfiguration entre le nom du client et la clé de connexion. */
    private static final String SEPARATOR = " - ";

    private final McpToolNamePrefixGenerator delegate;
    private final Map<String, String> connections = new ConcurrentHashMap<>();

    public ConnectionTrackingToolNames() {
        this(new DefaultMcpToolNamePrefixGenerator());
    }

    ConnectionTrackingToolNames(McpToolNamePrefixGenerator delegate) {
        this.delegate = delegate;
    }

    @Override
    public String prefixedToolName(McpConnectionInfo connectionInfo, McpSchema.Tool tool) {
        String name = delegate.prefixedToolName(connectionInfo, tool);
        connection(connectionInfo).ifPresent(connection -> connections.put(name, connection));
        return name;
    }

    /** La connexion d'un outil nommé ici, vide pour tout autre : le serveur reste alors inconnu. */
    public Optional<String> connectionOf(String toolName) {
        return Optional.ofNullable(connections.get(toolName));
    }

    // lastIndexOf : un nom de client qui contiendrait lui-même le séparateur ne doit pas rogner la
    // clé de connexion, qui n'en contient jamais (c'est une clé de configuration).
    static Optional<String> connection(McpConnectionInfo info) {
        String client = info == null || info.clientInfo() == null ? null : info.clientInfo().name();
        if (client == null) {
            return Optional.empty();
        }
        int at = client.lastIndexOf(SEPARATOR);
        return at < 0 || at + SEPARATOR.length() >= client.length()
                ? Optional.empty() : Optional.of(client.substring(at + SEPARATOR.length()));
    }
}
