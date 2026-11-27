// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors
package com.kex.agent.web;

import java.util.List;

import com.kex.agent.agent.SuggestionService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.assertj.MockMvcTester;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;

@WebMvcTest(ChatSuggestionController.class)
@AutoConfigureMockMvc(addFilters = false)
class ChatSuggestionControllerTest {

    @Autowired
    MockMvcTester mvc;

    @MockitoBean
    SuggestionService suggestions;

    @Test
    void rend_les_suggestions_et_leur_absence_avec_sa_raison() {
        given(suggestions.suggest("Anonyme", "Quels topics ?", "demo.app.topic"))
                .willReturn(new SuggestionService.Suggestions(List.of("Et le retard ?"), null));

        assertThat(mvc.post().uri("/api/agent/chat/suggestions").contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"question":"Quels topics ?","answer":"demo.app.topic"}"""))
                .hasStatusOk().bodyJson().extractingPath("$.suggestions[0]").isEqualTo("Et le retard ?");

        given(suggestions.suggest("Anonyme", "q", "r"))
                .willReturn(new SuggestionService.Suggestions(List.of(), "Le modèle n'a pas proposé de suggestion à temps"));
        // Pas une erreur : rien n'est à corriger côté appelant, et la console n'affiche simplement rien.
        assertThat(mvc.post().uri("/api/agent/chat/suggestions").contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"question":"q","answer":"r"}"""))
                .hasStatusOk().bodyJson().extractingPath("$.reason").asString().contains("à temps");
    }

    @Test
    void refuse_une_demande_sans_question_ou_sans_reponse() {
        assertThat(mvc.post().uri("/api/agent/chat/suggestions").contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"question":"","answer":"r"}""")).hasStatus4xxClientError();
        assertThat(mvc.post().uri("/api/agent/chat/suggestions").contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"question":"q"}""")).hasStatus4xxClientError();
    }
}
