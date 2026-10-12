// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors
package com.kex.agent.config;

import java.io.IOException;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kex.agent.tools.ToolControlProperties;
import com.kex.agent.tools.ToolInvocationPolicy;
import org.junit.jupiter.api.Test;
import org.springframework.ai.mcp.client.common.autoconfigure.properties.McpStreamableHttpClientProperties;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;
import org.springframework.core.io.ClassPathResource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * La connexion SpectraLLM telle que {@code compose/spectrallm.yml} la déclare : par variables
 * d'environnement, sur le vrai {@code application.yml}. Lu seul, aucun des deux fichiers ne
 * prouve que Spring relie {@code SPRING_AI_MCP_CLIENT_STREAMABLEHTTP_CONNECTIONS_SPECTRALLM_URL}
 * à la clé {@code spectrallm}, ni que la clé part dans {@code X-API-Key}.
 */
class SpectraLlmMcpConnectionTest {

    private static Binder binder(Map<String, Object> env) throws IOException {
        StandardEnvironment environment = new StandardEnvironment();
        // Sous le nom réservé, comme dans un vrai démarrage : c'est lui qui active la liaison souple
        // des noms de variables (SPRING_AI_..._SPECTRALLM_URL vers ...connections.spectrallm.url).
        environment.getPropertySources().replace(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
                new SystemEnvironmentPropertySource(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME, env));
        new YamlPropertySourceLoader().load("application", new ClassPathResource("application.yml"))
                .forEach(source -> environment.getPropertySources().addLast(source));
        return Binder.get(environment);
    }

    @Test
    void la_surcouche_ajoute_la_connexion_et_la_cle_part_dans_x_api_key() throws IOException {
        Binder binder = binder(Map.of(
                "SPECTRALLM_URL", "http://spectra:8080",
                "SPECTRA_API_KEY", "cle",
                "SPRING_AI_MCP_CLIENT_STREAMABLEHTTP_CONNECTIONS_SPECTRALLM_URL", "http://spectra:8080",
                "SPRING_AI_MCP_CLIENT_STREAMABLEHTTP_CONNECTIONS_SPECTRALLM_ENDPOINT", "/mcp"));

        McpStreamableHttpClientProperties http = binder
                .bind("spring.ai.mcp.client.streamable-http", McpStreamableHttpClientProperties.class).get();
        assertThat(http.getConnections()).containsKeys("kafka-explorer", "spectrallm");
        assertThat(http.getConnections().get("spectrallm").url()).isEqualTo("http://spectra:8080");

        McpAuthProperties auth = binder.bind("kex.mcp", McpAuthProperties.class).get();
        assertThat(auth.bearerTokens()).anySatisfy(token -> {
            assertThat(token.urlPrefix()).isEqualTo("http://spectra:8080");
            assertThat(token.token()).isEqualTo("cle");
            assertThat(token.header()).isEqualTo("X-API-Key");
        });
    }

    private static ToolInvocationPolicy policy(Binder binder) {
        return new ToolInvocationPolicy(binder.bind("kex.agent.tools", ToolControlProperties.class)
                .orElseGet(ToolControlProperties::defaults), new ObjectMapper());
    }

    @Test
    void l_agent_garde_la_recherche_de_spectrallm_et_perd_la_reponse_redigee_par_son_llm() throws IOException {
        ToolInvocationPolicy policy = policy(binder(Map.of()));

        assertThat(policy.isDenied("spectra_ask")).isTrue();
        assertThat(policy.isDenied("spectrallm__spectra_ask")).isTrue();
        assertThat(policy.isDenied("spectrallm__spectra_status")).isTrue();
        assertThat(policy.isDenied("spectrallm__spectra_search")).isFalse();
        assertThat(policy.isDenied("spectrallm__spectra_list_documents")).isFalse();
        assertThat(policy.isDenied("spectrallm__spectra_preview_document")).isFalse();
        assertThat(policy.isDenied("kafka-explorer__kex_list_topics")).isFalse();
    }

    @Test
    void un_exploitant_peut_rendre_spectra_ask_a_l_agent_par_variable_d_environnement() throws IOException {
        ToolInvocationPolicy policy = policy(binder(Map.of("KEX_SPECTRA_ASK_DENIED", "false")));

        assertThat(policy.isDenied("spectrallm__spectra_ask")).isFalse();
        assertThat(policy.isDenied("spectrallm__spectra_status")).isTrue();
    }

    @Test
    void sans_surcouche_aucune_connexion_spectrallm_et_l_entree_reste_inerte() throws IOException {
        Binder binder = binder(Map.of());

        assertThat(binder.bind("spring.ai.mcp.client.streamable-http", McpStreamableHttpClientProperties.class)
                .get().getConnections()).doesNotContainKey("spectrallm");
        assertThat(binder.bind("kex.mcp", McpAuthProperties.class).get().bearerTokens())
                .filteredOn(token -> "X-API-Key".equals(token.header()))
                .singleElement()
                .satisfies(token -> assertThat(token.urlPrefix()).isEmpty());
    }
}
