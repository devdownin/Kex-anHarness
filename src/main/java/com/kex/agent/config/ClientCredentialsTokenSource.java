// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors
package com.kex.agent.config;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.function.Supplier;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.util.StringUtils;

/**
 * Jeton d'accès OAuth2 obtenu par {@code client_credentials}, gardé jusqu'à peu avant son
 * expiration. Le customizer MCP est synchrone : l'appel d'obtention est bloquant, et sérialisé
 * pour que dix requêtes simultanées sur un jeton expiré n'en demandent qu'un.
 *
 * <p>Un échec d'obtention ne renvoie jamais le jeton périmé : un serveur qui répond 401 se
 * diagnostique, un jeton expiré servi en silence fait chercher la panne au mauvais endroit. Après
 * un échec, la prochaine requête retente ; il n'y a pas de repli à attendre.
 */
class ClientCredentialsTokenSource implements Supplier<String> {

    private static final Logger log = LoggerFactory.getLogger(ClientCredentialsTokenSource.class);

    /** Renouvelé tant qu'il reste moins que ça : couvre l'écart d'horloge et la durée de l'appel. */
    static final Duration RENEW_MARGIN = Duration.ofSeconds(30);

    private static final Duration FALLBACK_LIFETIME = Duration.ofMinutes(1);

    private final McpAuthProperties.ClientCredentials credentials;
    private final HttpClient http;
    private final ObjectMapper mapper;
    private final Clock clock;

    private String accessToken;
    private Instant renewAt = Instant.MIN;

    ClientCredentialsTokenSource(McpAuthProperties.ClientCredentials credentials, HttpClient http,
                                 ObjectMapper mapper, Clock clock) {
        this.credentials = credentials;
        this.http = http;
        this.mapper = mapper;
        this.clock = clock;
    }

    @Override
    public synchronized String get() {
        if (accessToken == null || !clock.instant().isBefore(renewAt)) {
            fetch();
        }
        return accessToken;
    }

    private void fetch() {
        try {
            HttpResponse<String> response = http.send(request(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() / 100 != 2) {
                throw new IllegalStateException("réponse " + response.statusCode());
            }
            JsonNode body = mapper.readTree(response.body());
            String token = body.path("access_token").asText("");
            if (token.isBlank()) {
                throw new IllegalStateException("réponse sans access_token");
            }
            long seconds = body.path("expires_in").asLong(FALLBACK_LIFETIME.toSeconds());
            this.accessToken = token;
            this.renewAt = clock.instant().plusSeconds(seconds).minus(RENEW_MARGIN);
        }
        catch (IOException | RuntimeException ex) {
            invalidate();
            log.warn("Jeton client_credentials non obtenu auprès de {} : {}", credentials.tokenUri(),
                    ex.getMessage());
            throw new IllegalStateException("Jeton MCP non obtenu", ex);
        }
        catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            invalidate();
            throw new IllegalStateException("Jeton MCP non obtenu : interrompu", ex);
        }
    }

    private void invalidate() {
        this.accessToken = null;
        this.renewAt = Instant.MIN;
    }

    private HttpRequest request() {
        StringBuilder form = new StringBuilder("grant_type=client_credentials");
        form.append("&client_id=").append(encode(credentials.clientId()));
        form.append("&client_secret=").append(encode(credentials.clientSecret()));
        if (StringUtils.hasText(credentials.scope())) {
            form.append("&scope=").append(encode(credentials.scope()));
        }
        return HttpRequest.newBuilder(URI.create(credentials.tokenUri()))
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(form.toString()))
                .build();
    }

    private static String encode(String value) {
        return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8);
    }
}
