// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors
package com.kex.agent.mcp;

import org.junit.jupiter.api.Test;
import org.springframework.ai.mcp.McpToolNamePrefixGenerator;
import org.springframework.ai.mcp.client.common.autoconfigure.McpToolCallbackAutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Le générateur n'a d'effet que si l'autoconfiguration de Spring AI s'efface devant lui. Sans ce
 * contrôle, un bean ignoré se verrait seulement à l'usage : la table des connexions resterait vide,
 * et chaque outil retomberait sans bruit sur le disjoncteur commun.
 */
class ConnectionTrackingToolNamesWiringTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(McpToolCallbackAutoConfiguration.class))
            .withPropertyValues("spring.ai.mcp.client.enabled=true");

    @Test
    void l_autoconfiguration_s_efface_devant_le_generateur_de_l_agent() {
        runner.withBean("mcpToolNamePrefixGenerator", ConnectionTrackingToolNames.class).run(context -> {
            assertThat(context.getBeansOfType(McpToolNamePrefixGenerator.class)).hasSize(1);
            assertThat(context.getBean(McpToolNamePrefixGenerator.class)).isInstanceOf(ConnectionTrackingToolNames.class);
        });
    }

    @Test
    void sans_le_generateur_de_l_agent_c_est_celui_de_spring_ai_qui_s_applique() {
        runner.run(context -> assertThat(context.getBean(McpToolNamePrefixGenerator.class))
                .isNotInstanceOf(ConnectionTrackingToolNames.class));
    }
}
