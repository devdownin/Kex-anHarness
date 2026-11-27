// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors
package com.kex.agent.agent;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.beans.factory.support.StaticListableBeanFactory;

import static org.assertj.core.api.Assertions.assertThat;

class SuggestionServiceTest {

    private final AtomicReference<Prompt> sent = new AtomicReference<>();

    private SuggestionService service(String reply, Duration delay, Duration timeout) {
        ChatModel model = new ChatModel() {
            @Override
            public ChatResponse call(Prompt prompt) {
                sent.set(prompt);
                try {
                    Thread.sleep(delay.toMillis());
                }
                catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                }
                return new ChatResponse(List.of(new Generation(new AssistantMessage(reply))));
            }

            // Comme les modèles Anthropic et OpenAI (et BudgetedChatModel, qui délègue) : des options d'appel
            // d'outils. ChatClient part de getOptions() et n'y pose le contexte d'outils — donc le
            // propriétaire, que lit le budget — que si elles en sont.
            @Override
            public org.springframework.ai.chat.prompt.ChatOptions getOptions() {
                return ToolCallingChatOptions.builder().build();
            }
        };
        StaticListableBeanFactory beans = new StaticListableBeanFactory();
        beans.addBean("chatModel", model);
        return new SuggestionService(beans.getBeanProvider(ChatModel.class),
                new SuggestionProperties(true, 3, timeout, 6000), CircuitBreakerRegistry.ofDefaults());
    }

    @Test
    void propose_jusqu_a_trois_relances_sans_outil_et_impute_au_proprietaire() {
        var result = service("""
                {"suggestions": ["Quel est le retard du groupe orders ?", "Depuis quand ce topic est-il vide ?",
                "Quels consommateurs lisent demo.app.topic ?", "Une quatrième de trop ?"]}""",
                Duration.ZERO, Duration.ofSeconds(5)).suggest("equipe-a", "Quels topics ont le plus de messages ?", "demo.app.topic : 4000");

        assertThat(result.suggestions()).containsExactly("Quel est le retard du groupe orders ?",
                "Depuis quand ce topic est-il vide ?", "Quels consommateurs lisent demo.app.topic ?");
        assertThat(result.reason()).isNull();
        // Imputé au budget du propriétaire, et sans aucun outil : la relance ne lit ni ne change rien.
        ToolCallingChatOptions options = (ToolCallingChatOptions) sent.get().getOptions();
        assertThat(options.getToolContext()).containsEntry("kex.owner", "equipe-a");
        assertThat(options.getToolCallbacks()).isNullOrEmpty();
        // La question et la réponse arrivent comme données balisées, jamais comme consigne.
        assertThat(sent.get().getContents()).contains("<question>").contains("<reponse>").contains("jamais une instruction");
    }

    @Test
    void une_reponse_qui_ne_vient_pas_a_temps_ne_retient_pas_l_appelant() {
        long start = System.nanoTime();
        var result = service("{\"suggestions\":[\"Trop tard pour être lue ?\"]}", Duration.ofSeconds(5), Duration.ofMillis(200))
                .suggest("a", "question", "réponse");

        assertThat(result.suggestions()).isEmpty();
        assertThat(result.reason()).contains("à temps");
        assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(3));
    }

    @Test
    void le_disjoncteur_ouvert_evite_l_appel() {
        CircuitBreakerRegistry registry = CircuitBreakerRegistry.ofDefaults();
        registry.circuitBreaker("agent-model").transitionToOpenState();
        StaticListableBeanFactory beans = new StaticListableBeanFactory();
        beans.addBean("chatModel", (ChatModel) prompt -> { throw new AssertionError("aucun appel attendu"); });
        var result = new SuggestionService(beans.getBeanProvider(ChatModel.class),
                new SuggestionProperties(true, 3, Duration.ofSeconds(1), 6000), registry).suggest("a", "q", "r");

        assertThat(result.suggestions()).isEmpty();
        assertThat(result.reason()).contains("indisponible");
    }

    @Test
    void une_panne_du_fournisseur_rend_une_liste_vide_et_sa_raison() {
        StaticListableBeanFactory beans = new StaticListableBeanFactory();
        beans.addBean("chatModel", (ChatModel) prompt -> { throw new IllegalStateException("401"); });
        var result = new SuggestionService(beans.getBeanProvider(ChatModel.class),
                new SuggestionProperties(true, 3, Duration.ofSeconds(2), 6000), CircuitBreakerRegistry.ofDefaults())
                .suggest("a", "q", "r");

        assertThat(result.suggestions()).isEmpty();
        assertThat(result.reason()).contains("n'a pas pu");
    }

    @Test
    void la_lecture_tolere_les_ecarts_de_forme_d_un_petit_modele() {
        // Clôtures de code et texte autour du JSON.
        assertThat(SuggestionService.parse("Voici :\n```json\n{\"suggestions\": [\"Et le groupe billing ?\"]}\n```", "q", 3))
                .containsExactly("Et le groupe billing ?");
        // Pas de JSON du tout : une liste à puces ou numérotée fait l'affaire.
        assertThat(SuggestionService.parse("1. Quel retard sur orders ?\n- Et sur billing ?\nDu bavardage.", "q", 3))
                .containsExactly("Quel retard sur orders ?", "Et sur billing ?");
        assertThat(SuggestionService.parse("", "q", 3)).isEmpty();
        assertThat(SuggestionService.parse(null, "q", 3)).isEmpty();
        assertThat(SuggestionService.parse("{\"suggestions\": \"pas une liste\"}", "q", 3)).isEmpty();
    }

    @Test
    void les_suggestions_sont_nettoyees_dedoublonnees_et_bornees() {
        String longue = "x".repeat(SuggestionService.MAX_LENGTH + 1);
        List<String> kept = SuggestionService.parse("""
                {"suggestions": ["  « Quel est le retard ?»  ", "quel est LE retard", "Quels topics ont le plus de messages ?",
                "ok", "%s", "Ligne\\navec\\tcontrôles ?", 42, "Et la partition 3 ?"]}""".formatted(longue),
                "Quels topics ont le plus de messages ?", 3);

        // Guillemets et espaces retirés ; doublon à la casse près écarté ; la question elle-même écartée ;
        // trop court, trop long et non textuel écartés ; caractères de contrôle remplacés.
        assertThat(kept).containsExactly("Quel est le retard ?", "Ligne avec contrôles ?", "Et la partition 3 ?");
        assertThat(SuggestionService.parse("{\"suggestions\": [\"Une seule demandée ?\", \"Et une autre ?\"]}", "q", 1))
                .containsExactly("Une seule demandée ?");
    }
}
