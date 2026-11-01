// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors
package com.kex.agent.harness;

import java.util.ArrayList;
import java.util.List;

import org.springframework.ai.chat.messages.Message;

/** État privé d'un échange : réapplique le même résumé aux tours suivants de sa boucle d'outils. */
public final class ContextSession {
    public static final String KEY = "kex.context-session";
    public static final String MEMORY = "kex.memory-id";
    private final List<Replacement> replacements = new ArrayList<>();

    synchronized List<Message> apply(List<Message> messages) {
        List<Message> current = List.copyOf(messages);
        for (var replacement : replacements) current = replace(current, replacement.before(), replacement.after());
        return current;
    }
    synchronized void remember(List<Message> before, Message after) {
        replacements.add(new Replacement(List.copyOf(before), after));
    }
    static List<Message> replace(List<Message> messages, List<Message> before, Message after) {
        int start = java.util.Collections.indexOfSubList(messages, before);
        if (start < 0) return messages;
        var next = new ArrayList<>(messages.subList(0, start));
        next.add(after);
        next.addAll(messages.subList(start + before.size(), messages.size()));
        return next;
    }
    private record Replacement(List<Message> before, Message after) { }
}
