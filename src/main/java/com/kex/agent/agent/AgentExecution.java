// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors
package com.kex.agent.agent;

import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicBoolean;

/** Garde partagée avec les outils pour qu'un échange expiré ne déclenche plus d'effet. */
public final class AgentExecution {

    public static final String CONTEXT_KEY = "kex.execution";

    private final AtomicBoolean cancelled = new AtomicBoolean();
    private final AgentExecution parent;
    private final long deadline;

    public AgentExecution() { this(null, Long.MAX_VALUE); }
    private AgentExecution(AgentExecution parent, long deadline) {
        this.parent = parent; this.deadline = deadline;
    }
    public AgentExecution child(java.time.Duration timeout) {
        return new AgentExecution(this, System.nanoTime() + timeout.toNanos());
    }

    public void cancel() {
        cancelled.set(true);
    }

    public void ensureActive() {
        if (parent != null) parent.ensureActive();
        if (cancelled.get() || Thread.currentThread().isInterrupted()
                || deadline != Long.MAX_VALUE && System.nanoTime() - deadline >= 0) {
            throw new CancellationException("Échange annulé");
        }
    }

    public static void ensureActive(org.springframework.ai.chat.model.ToolContext context) {
        Object execution = context == null ? null : context.getContext().get(CONTEXT_KEY);
        if (execution instanceof AgentExecution active) {
            active.ensureActive();
        }
    }
}
