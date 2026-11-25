// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors
package com.kex.agent.mcp;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Map;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Un outil {@code governed-only} disparaît du catalogue vu par le modèle et refuse l'appel direct,
 * mais reste atteignable par une action de supervision. Le banc de bout en bout de la suite
 * Convergence a montré le défaut : {@code denied}, seul moyen de cacher declarer_anomalies au
 * modèle, bloquait aussi l'action approuvée qui devait l'appeler.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("mcp-it")
class McpGovernedOnlyIntegrationTest {

    private static final FakeMcpServer SERVER = start();

    @Autowired
    McpToolCatalog catalog;

    private static FakeMcpServer start() {
        try {
            return new FakeMcpServer();
        }
        catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }

    @DynamicPropertySource
    static void mcpProperties(DynamicPropertyRegistry registry) {
        String url = "http://127.0.0.1:" + SERVER.port();
        registry.add("spring.ai.mcp.client.streamable-http.connections.faux.url", () -> url);
        registry.add("spring.ai.mcp.client.streamable-http.connections.faux.endpoint", () -> "/mcp");
        registry.add("kex.mcp.bearer-tokens[0].url-prefix", () -> url);
        registry.add("kex.mcp.bearer-tokens[0].token", () -> FakeMcpServer.TOKEN);
        registry.add("kex.agent.tools.rules.echo.governed-only", () -> "true");
    }

    @AfterAll
    static void stop() {
        SERVER.close();
    }

    @Test
    void un_outil_reserve_aux_actions_gouvernees_est_cache_et_refuse_en_direct_mais_reste_appelable_par_une_action() {
        assertThat(catalog.isToolAllowed("faux", "echo")).isFalse();
        assertThat(catalog.isReadOnly("faux", "echo")).isFalse();
        assertThatThrownBy(() -> catalog.call("faux", "echo", Map.of("texte", "ping")))
                .isInstanceOf(McpToolForbiddenException.class);

        assertThat(catalog.callGoverned("faux", "echo", Map.of("texte", "ping")).content()).containsExactly("pong");
    }
}
