// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors
package com.kex.agent.harness;

import java.util.function.Supplier;

import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Schedulers;

/** Placé avant la réservation fournisseur ; le résumé utilise le même modèle déjà budgété. */
public final class ContextManagingChatModel implements ChatModel {
    private final ChatModel delegate;
    private final Supplier<ContextCompactor> compactor;
    public ContextManagingChatModel(ChatModel delegate, Supplier<ContextCompactor> compactor) {
        this.delegate = delegate; this.compactor = compactor;
    }
    @Override public ChatOptions getOptions() { return delegate.getOptions(); }
    @Override public ChatOptions getDefaultOptions() { return delegate.getDefaultOptions(); }
    @Override public ChatResponse call(Prompt prompt) { return delegate.call(compactor.get().prepare(prompt, delegate)); }
    @Override public Flux<ChatResponse> stream(Prompt prompt) {
        // Le résumé est bloquant ; ne pas bloquer le thread Reactor de l'appelant.
        return Flux.defer(() -> delegate.stream(compactor.get().prepare(prompt, delegate))).subscribeOn(Schedulers.boundedElastic());
    }
}
