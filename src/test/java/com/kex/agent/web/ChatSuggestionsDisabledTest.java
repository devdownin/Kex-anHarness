// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors
package com.kex.agent.web;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code kex.agent.suggestions.enabled=false} débraye les relances pour toute l'installation : la route
 * disparaît, aucun appel au modèle n'est possible, et {@code whoami} le dit pour que la console cache son
 * interrupteur plutôt que de promettre ce qui n'existe pas.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"kex.agent.api-keys.chat-only=jeton-chat", "kex.agent.rate-limit.enabled=false",
                "kex.agent.suggestions.enabled=false"})
@ActiveProfiles("test")
class ChatSuggestionsDisabledTest {

    @LocalServerPort
    int port;

    @Test
    void la_route_disparait_et_whoami_le_declare() throws IOException, InterruptedException {
        HttpResponse<String> whoami = send(HttpRequest.newBuilder(uri("/api/agent/whoami"))
                .header("Authorization", "Bearer jeton-chat").build());
        assertThat(whoami.body()).contains("\"suggestions\":false");

        HttpResponse<String> suggestions = send(HttpRequest.newBuilder(uri("/api/agent/chat/suggestions"))
                .header("Authorization", "Bearer jeton-chat").header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"question\":\"q\",\"answer\":\"r\"}")).build());
        assertThat(suggestions.statusCode()).isEqualTo(404);
    }

    private URI uri(String path) {
        return URI.create("http://localhost:" + port + path);
    }

    private static HttpResponse<String> send(HttpRequest request) throws IOException, InterruptedException {
        try (HttpClient client = HttpClient.newHttpClient()) {
            return client.send(request, HttpResponse.BodyHandlers.ofString());
        }
    }
}
