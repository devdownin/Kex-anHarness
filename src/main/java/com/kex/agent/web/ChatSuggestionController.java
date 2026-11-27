// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors
package com.kex.agent.web;

import java.security.Principal;
import java.util.List;

import com.kex.agent.agent.SuggestionService;
import com.kex.agent.config.ActorIdentity;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code POST /api/agent/chat/suggestions} : les questions de relance d'une réponse.
 *
 * <p>Sous {@code /api/agent/chat}, donc sous la même limite de débit et le même rôle qu'un échange :
 * c'en est un, avec le modèle. Absente quand {@code kex.agent.suggestions.enabled} est faux — et
 * {@code whoami} le dit à la console, qui cache alors son interrupteur plutôt que d'appeler une route
 * qui n'existe pas.
 */
@RestController
@RequestMapping("/api/agent/chat")
@ConditionalOnProperty(prefix = "kex.agent.suggestions", name = "enabled", havingValue = "true", matchIfMissing = true)
class ChatSuggestionController {

    private final SuggestionService suggestions;

    ChatSuggestionController(SuggestionService suggestions) {
        this.suggestions = suggestions;
    }

    record SuggestionRequest(@NotBlank @Size(max = 32_000) String question,
                             @NotBlank @Size(max = 200_000) String answer) { }

    record SuggestionResponse(List<String> suggestions, String reason) { }

    @PostMapping("/suggestions")
    SuggestionResponse suggest(@Valid @RequestBody SuggestionRequest request, Principal principal) {
        String tenant = ActorIdentity.tenantOf(principal);
        SuggestionService.Suggestions result = suggestions.suggest(tenant == null ? "Anonyme" : tenant,
                request.question(), request.answer());
        return new SuggestionResponse(result.suggestions(), result.reason());
    }
}
