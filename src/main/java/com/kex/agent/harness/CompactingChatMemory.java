// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors
package com.kex.agent.harness;

import java.util.ArrayList;
import java.util.List;

import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.ai.chat.messages.Message;

/** Conserve le contexte jusqu'à son résumé ; la comparaison de préfixe protège les écritures concurrentes. */
public final class CompactingChatMemory implements ChatMemory {
    private final ChatMemoryRepository repository;
    private final ConversationLock locks;
    private final int maxCharacters;

    public CompactingChatMemory(ChatMemoryRepository repository, ConversationLock locks, int maxCharacters) {
        this.repository = repository; this.locks = locks; this.maxCharacters = maxCharacters;
    }
    @Override public void add(String id, List<Message> messages) {
        locks.with("history:" + id, () -> {
            var all = new ArrayList<>(repository.findByConversationId(id));
            all.addAll(messages);
            long size = all.stream().mapToLong(m -> m.getText() == null ? 0 : m.getText().length()).sum();
            if (size > maxCharacters) throw new IllegalArgumentException("Historique trop volumineux ; ouvrir une nouvelle conversation");
            repository.saveAll(id, all);
            return null;
        });
    }
    @Override public void add(String id, Message message) { add(id, List.of(message)); }
    @Override public List<Message> get(String id) {
        return locks.with("history:" + id, () -> List.copyOf(repository.findByConversationId(id)));
    }
    @Override public void clear(String id) {
        locks.with("history:" + id, () -> { repository.deleteByConversationId(id); return null; });
    }
    public boolean replacePrefix(String id, List<Message> expected, Message summary) {
        return locks.with("history:" + id, () -> {
            var current = repository.findByConversationId(id);
            if (current.size() < expected.size() || !current.subList(0, expected.size()).equals(expected)) return false;
            var next = new ArrayList<Message>();
            next.add(summary);
            next.addAll(current.subList(expected.size(), current.size()));
            repository.saveAll(id, next);
            return true;
        });
    }
}
