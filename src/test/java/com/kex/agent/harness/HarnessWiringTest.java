// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors
package com.kex.agent.harness;

import com.kex.agent.KexAgentApplication;
import com.kex.agent.agent.AgentService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

class HarnessWiringTest {
    @TempDir java.nio.file.Path directory;

    @Test
    void le_contexte_complet_active_les_quatre_capacites_et_reste_bloque_sans_outil_autorise() {
        runner().withPropertyValues("spring.autoconfigure.exclude=org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration,"
                        + "org.springframework.ai.model.chat.memory.repository.jdbc.autoconfigure.JdbcChatMemoryRepositoryAutoConfiguration,"
                        + "org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration")
                .run(this::assertActive);
    }

    @Test
    void le_profil_partage_supporte_les_identifiants_prives_et_les_verrous_du_harness() {
        runner().withPropertyValues("spring.profiles.active=shared-memory",
                        "spring.datasource.url=jdbc:h2:mem:harness-wiring;DB_CLOSE_DELAY=-1",
                        "spring.datasource.username=sa", "spring.datasource.password=",
                        "spring.datasource.driver-class-name=org.h2.Driver",
                        "spring.ai.chat.memory.repository.jdbc.platform=h2")
                .run(ctx -> {
                    assertActive(ctx);
                    assertThat(ctx.getBean(ToolResultRepository.class)).isInstanceOf(JdbcToolResultRepository.class);
                    assertThat(ctx.getBean(org.springframework.ai.chat.memory.ChatMemoryRepository.class).getClass().getName())
                            .contains("Jdbc");
                });
    }

    private ApplicationContextRunner runner() {
        return new ApplicationContextRunner().withUserConfiguration(KexAgentApplication.class)
                .withBean(ChatModel.class, () -> HarnessFixtures.model(prompt -> {
                    var options = (org.springframework.ai.model.tool.ToolCallingChatOptions) prompt.getOptions();
                    assertThat(options.getToolCallbacks() == null ? java.util.List.of() : options.getToolCallbacks()).isEmpty();
                    return HarnessFixtures.answer("Aucun outil autorisé ; observation indisponible.");
                })).withPropertyValues(
                        "spring.ai.model.chat=none", "spring.ai.model.embedding=none", "spring.ai.mcp.client.enabled=false",
                        "spring.ai.openai.api-key=cle-test",
                        "spring.ai.model.audio.speech=none", "spring.ai.model.audio.transcription=none",
                        "spring.ai.model.image=none", "spring.ai.model.moderation=none",
                        "kex.agent.api-key=test", "kex.agent.harness.results.enabled=true",
                        "kex.agent.harness.compaction.enabled=true", "kex.agent.harness.discovery.enabled=true",
                        "kex.agent.harness.delegation.enabled=true",
                        "kex.agent.harness.results.store-path=" + directory.resolve("results.json"),
                        "kex.agent.memory.storage-directory=" + directory.resolve("memory"),
                        "kex.agent.token-budget.store-path=" + directory.resolve("budgets.json"));
    }
    private void assertActive(org.springframework.boot.test.context.assertj.AssertableApplicationContext ctx) {
                    assertThat(ctx).hasNotFailed();
                    assertThat(ctx.getBean(ChatMemory.class)).isInstanceOf(CompactingChatMemory.class);
                    assertThat(ctx.getBean(ChatModel.class)).isInstanceOf(ContextManagingChatModel.class);
                    var answer = ctx.getBean(AgentService.class).askReadOnly("tenant", "conv", "Analyser orders", java.util.Set.of());
                    assertThat(answer.content()).contains("Aucun outil autorisé");
                    // Le périmètre des résultats change avec les rôles, même sans KnowledgeService.
                    var scopes = new java.util.ArrayList<String>();
                    var model = HarnessFixtures.model(prompt -> {
                        var options = (org.springframework.ai.model.tool.ToolCallingChatOptions) prompt.getOptions();
                        scopes.add((String) options.getToolContext().get(StoredToolResults.SCOPE));
                        return HarnessFixtures.answer("ok");
                    });
                    // Vérifier la formule par le vrai AgentService : substituer son ChatClient conserve
                    // la préparation de contexte et évite toute dépendance à un fournisseur.
                    var agent = ctx.getBean(AgentService.class);
                    Object previous = org.springframework.test.util.ReflectionTestUtils.getField(agent, "chatClient");
                    org.springframework.test.util.ReflectionTestUtils.setField(agent, "chatClient", org.springframework.ai.chat.client.ChatClient.create(model));
                    try {
                        for (String role : java.util.List.of("OPERATOR", "CHAT")) {
                            var auth = new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(
                                    new com.kex.agent.config.ActorIdentity("actor", "tenant"), "",
                                    java.util.List.of(new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_" + role)));
                            org.springframework.security.core.context.SecurityContextHolder.getContext().setAuthentication(auth);
                            agent.askReadOnly("tenant", "same-conversation", "périmètre", java.util.Set.of());
                        }
                        assertThat(scopes).hasSize(2);
                        assertThat(scopes.get(0)).isNotEqualTo(scopes.get(1));
                    }
                    finally {
                        org.springframework.security.core.context.SecurityContextHolder.clearContext();
                        org.springframework.test.util.ReflectionTestUtils.setField(agent, "chatClient", previous);
                    }
    }
}
