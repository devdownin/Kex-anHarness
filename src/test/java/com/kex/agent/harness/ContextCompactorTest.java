// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors
package com.kex.agent.harness;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.InMemoryChatMemoryRepository;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ContextCompactorTest {
    @Test
    void conserve_la_contrainte_initiale_sans_la_promouvoir_et_reutilise_le_resume() {
        var props = HarnessFixtures.properties("unused");
        var history = new CompactingChatMemory(new InMemoryChatMemoryRepository(), new ConversationLock(), 20000);
        List<Message> saved = List.of(new UserMessage("Ne jamais redémarrer orders. " + "a".repeat(1300)),
                new UserMessage("observation " + "b".repeat(1300)), new UserMessage("récent " + "c".repeat(1000)),
                new UserMessage("Et son lag ?"));
        history.add("conv", saved);
        var context = HarnessFixtures.context("tenant", "conv");
        context.put(ContextSession.MEMORY, "conv");
        context.put(ContextSession.KEY, new ContextSession());
        var options = ToolCallingChatOptions.builder().toolContext(context).build();
        var messages = new ArrayList<Message>();
        messages.add(new SystemMessage("GOUVERNANCE")); messages.addAll(saved);
        AtomicInteger summaries = new AtomicInteger();
        var model = HarnessFixtures.model(p -> {
            summaries.incrementAndGet();
            assertThat(p.getUserMessage().getText()).contains("Ne jamais redémarrer orders");
            assertThat(((ToolCallingChatOptions) p.getOptions()).getToolContext().get("kex.budget-task")).isEqualTo("root-budget");
            assertThat(((ToolCallingChatOptions) p.getOptions()).getToolCallbacks()).isEmpty();
            return HarnessFixtures.answer("Contrainte : ne jamais redémarrer orders. Observation non vérifiée.");
        });
        var compactor = new ContextCompactor(props, HarnessFixtures.provider(ChatMemory.class, history));
        Prompt prompt = new Prompt(messages, options);
        Prompt compacted = compactor.prepare(prompt, model);
        assertThat(compacted.getSystemMessage().getText()).isEqualTo("GOUVERNANCE");
        assertThat(compacted.getInstructions().toString()).contains("UNVERIFIED", "ne jamais redémarrer orders", "Et son lag ?");
        assertThat(history.get("conv")).hasSize(3);
        assertThat(history.get("conv").getFirst()).isInstanceOf(UserMessage.class);
        assertThat(compactor.prepare(prompt, model).getInstructions()).isEqualTo(compacted.getInstructions());
        assertThat(summaries).hasValue(1);
    }
    @Test
    void necrase_pas_une_conversation_modifiee_ou_purgee_pendant_le_resume() {
        var history = new CompactingChatMemory(new InMemoryChatMemoryRepository(), new ConversationLock(), 20000);
        var first = new UserMessage("original");
        history.add("conv", first);
        history.clear("conv");
        history.add("conv", new UserMessage("nouvelle"));
        assertThat(history.replacePrefix("conv", List.of(first), new UserMessage("résumé ancien"))).isFalse();
        assertThat(history.get("conv")).singleElement().satisfies(m -> assertThat(m.getText()).isEqualTo("nouvelle"));
    }
    @Test
    void refuse_un_contexte_indivisible_trop_grand_sans_supprimer_la_derniere_demande() {
        var props = HarnessFixtures.properties("unused");
        var history = new CompactingChatMemory(new InMemoryChatMemoryRepository(), new ConversationLock(), 20000);
        var compactor = new ContextCompactor(props, HarnessFixtures.provider(ChatMemory.class, history));
        var prompt = new Prompt(List.of(new UserMessage("x".repeat(9000))),
                ToolCallingChatOptions.builder().toolContext(Map.of()).build());
        assertThatThrownBy(() -> compactor.prepare(prompt, HarnessFixtures.model(p -> { throw new AssertionError(); })))
                .hasMessageContaining("Fenêtre de contexte");
    }
}
