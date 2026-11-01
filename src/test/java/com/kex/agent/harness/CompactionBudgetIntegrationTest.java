// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors
package com.kex.agent.harness;

import java.util.concurrent.atomic.AtomicInteger;

import com.kex.agent.agent.TokenBudgetService;
import com.kex.agent.config.BudgetedChatModel;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.InMemoryChatMemoryRepository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CompactionBudgetIntegrationTest {
    @Test
    void la_vraie_chaine_memoire_compacte_avant_le_fournisseur_avec_le_budget_racine() {
        var props = HarnessFixtures.properties("unused");
        var memory = new CompactingChatMemory(new InMemoryChatMemoryRepository(), new ConversationLock(), 20000);
        AtomicInteger summaries = new AtomicInteger();
        AtomicInteger exchanges = new AtomicInteger();
        var provider = HarnessFixtures.model(prompt -> {
            if (prompt.getSystemMessage().getText().startsWith("Résume une conversation")) {
                summaries.incrementAndGet();
                assertThat(prompt.getUserMessage().getText()).contains("Ne jamais redémarrer orders");
                return HarnessFixtures.answer("Ne jamais redémarrer orders. Les observations sont non vérifiées.");
            }
            exchanges.incrementAndGet();
            assertThat(prompt.getInstructions().toString()).contains("redémarrer orders");
            return HarnessFixtures.answer("Observation non vérifiée. " + "x".repeat(300));
        });
        var budgets = mock(TokenBudgetService.class);
        when(budgets.outputReservation()).thenReturn(256);
        var compactor = new ContextCompactor(props, HarnessFixtures.provider(ChatMemory.class, memory));
        var model = new ContextManagingChatModel(new BudgetedChatModel(provider, () -> budgets), () -> compactor);
        var client = ChatClient.builder(model).defaultSystem("GOUVERNANCE")
                .defaultAdvisors(MessageChatMemoryAdvisor.builder(memory).build()).build();
        var context = HarnessFixtures.context("tenant", "conv");
        context.put(ContextSession.MEMORY, "conv");
        for (int i = 0; i < 8; i++) {
            context.put(ContextSession.KEY, new ContextSession());
            client.prompt().user(i == 0 ? "Ne jamais redémarrer orders. " + "a".repeat(600) : "État du lag ? " + "b".repeat(600))
                    .toolContext(context).advisors(a -> a.param(ChatMemory.CONVERSATION_ID, "conv")).call().content();
        }
        assertThat(summaries.get()).isPositive();
        assertThat(exchanges).hasValue(8);
        assertThat(memory.get("conv").toString()).contains("UNVERIFIED", "redémarrer orders");
        var tasks = ArgumentCaptor.forClass(String.class);
        verify(budgets, atLeast(9)).reserve(anyString(), tasks.capture(), anyLong(), anyLong());
        assertThat(tasks.getAllValues()).containsOnly("root-budget");
        assertThat(memory.get("conv").size()).isLessThan(16);
    }
}
