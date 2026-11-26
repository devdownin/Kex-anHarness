// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors
package com.kex.agent.mcp;

import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.Test;
import org.springframework.ai.mcp.DefaultMcpToolNamePrefixGenerator;
import org.springframework.ai.mcp.McpConnectionInfo;

import static org.assertj.core.api.Assertions.assertThat;

class ConnectionTrackingToolNamesTest {

    private static McpConnectionInfo connection(String clientName) {
        return new McpConnectionInfo(null, new McpSchema.Implementation(clientName, "1.0"), null);
    }

    private static McpSchema.Tool tool(String name) {
        return McpSchema.Tool.builder().name(name).description(name).build();
    }

    @Test
    void les_noms_restent_ceux_de_spring_ai_et_la_connexion_est_retenue() {
        ConnectionTrackingToolNames names = new ConnectionTrackingToolNames();
        DefaultMcpToolNamePrefixGenerator reference = new DefaultMcpToolNamePrefixGenerator();

        String name = names.prefixedToolName(connection("spring-ai-mcp-client - kafka-explorer"), tool("kex_list_topics"));

        assertThat(name).isEqualTo(reference.prefixedToolName(connection("spring-ai-mcp-client - kafka-explorer"),
                tool("kex_list_topics")));
        assertThat(names.connectionOf(name)).contains("kafka-explorer");
    }

    @Test
    void deux_connexions_donnent_deux_outils_et_deux_connexions() {
        ConnectionTrackingToolNames names = new ConnectionTrackingToolNames();

        String first = names.prefixedToolName(connection("spring-ai-mcp-client - alpha"), tool("search"));
        String second = names.prefixedToolName(connection("spring-ai-mcp-client - beta"), tool("search"));

        assertThat(first).isNotEqualTo(second);
        assertThat(names.connectionOf(first)).contains("alpha");
        assertThat(names.connectionOf(second)).contains("beta");
    }

    @Test
    void un_nom_de_client_qui_contient_le_separateur_ne_rogne_pas_la_cle() {
        assertThat(ConnectionTrackingToolNames.connection(connection("mon - client - kafka-explorer")))
                .contains("kafka-explorer");
    }

    @Test
    void un_nom_sans_connexion_lisible_laisse_l_outil_inconnu_plutot_que_mal_attribue() {
        ConnectionTrackingToolNames names = new ConnectionTrackingToolNames();

        assertThat(ConnectionTrackingToolNames.connection(connection("spring-ai-mcp-client"))).isEmpty();
        assertThat(ConnectionTrackingToolNames.connection(connection("spring-ai-mcp-client - "))).isEmpty();
        assertThat(ConnectionTrackingToolNames.connection(null)).isEmpty();
        assertThat(names.connectionOf("outil_jamais_vu")).isEmpty();
    }
}
