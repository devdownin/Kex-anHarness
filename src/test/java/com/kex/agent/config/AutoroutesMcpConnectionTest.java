// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors
package com.kex.agent.config;

import java.io.IOException;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.ai.mcp.client.common.autoconfigure.properties.McpStreamableHttpClientProperties;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;
import org.springframework.core.io.ClassPathResource;

import static org.assertj.core.api.Assertions.assertThat;

class AutoroutesMcpConnectionTest {
    private Binder binder(Map<String, Object> env) throws IOException {
        var environment = new StandardEnvironment();
        environment.getPropertySources().replace(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
                new SystemEnvironmentPropertySource(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME, env));
        new YamlPropertySourceLoader().load("application", new ClassPathResource("application.yml"))
                .forEach(source -> environment.getPropertySources().addLast(source));
        return Binder.get(environment);
    }

    @Test
    void connexion_et_jeton_sont_lies_a_la_surcouche_optionnelle() throws IOException {
        var binder = binder(Map.of("AUTOROUTES_MCP_URL", "http://mcp-anomalies:8090", "AUTOROUTES_MCP_TOKEN", "jeton-test",
                "SPRING_AI_MCP_CLIENT_STREAMABLEHTTP_CONNECTIONS_MCPAUTOROUTE_URL", "http://mcp-anomalies:8090",
                "SPRING_AI_MCP_CLIENT_STREAMABLEHTTP_CONNECTIONS_MCPAUTOROUTE_ENDPOINT", "/mcp"));
        var connections = binder.bind("spring.ai.mcp.client.streamable-http", McpStreamableHttpClientProperties.class)
                .get().getConnections();
        assertThat(connections).containsKeys("kafka-explorer", "mcpautoroute");
        assertThat(connections.get("mcpautoroute").url()).isEqualTo("http://mcp-anomalies:8090");
        assertThat(connections.get("mcpautoroute").endpoint()).isEqualTo("/mcp");
        assertThat(binder.bind("kex.mcp", McpAuthProperties.class).get().bearerTokens()).anySatisfy(token -> {
            assertThat(token.urlPrefix()).isEqualTo("http://mcp-anomalies:8090");
            assertThat(token.token()).isEqualTo("jeton-test");
            assertThat(token.header()).isNull();
        });
    }

    @Test
    void aucune_connexion_autoroutes_par_defaut() throws IOException {
        assertThat(binder(Map.of()).bind("spring.ai.mcp.client.streamable-http", McpStreamableHttpClientProperties.class)
                .get().getConnections()).doesNotContainKey("mcpautoroute");
        assertThat(binder(Map.of()).bind("kex.mcp", McpAuthProperties.class).get().bearerTokens())
                .noneSatisfy(token -> assertThat(token.urlPrefix()).contains("mcp-anomalies"));
    }
}
