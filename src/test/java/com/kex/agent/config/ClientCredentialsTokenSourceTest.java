// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors
package com.kex.agent.config;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ClientCredentialsTokenSourceTest {

    private HttpServer server;
    private final AtomicInteger calls = new AtomicInteger();
    private final AtomicReference<String> lastForm = new AtomicReference<>();
    private volatile int status = 200;
    private volatile String body;
    private MutableClock clock;

    @BeforeEach
    void start() throws IOException {
        clock = new MutableClock(Instant.parse("2026-11-14T10:00:00Z"));
        body = json("jeton-1", 300);
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/token", exchange -> {
            calls.incrementAndGet();
            lastForm.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] payload = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, payload.length);
            exchange.getResponseBody().write(payload);
            exchange.close();
        });
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private ClientCredentialsTokenSource source() {
        return new ClientCredentialsTokenSource(new McpAuthProperties.ClientCredentials(
                "http://127.0.0.1:" + server.getAddress().getPort() + "/token", "kex-supervisor",
                "s e&cret", "openid"), HttpClient.newHttpClient(), new ObjectMapper(), clock);
    }

    private static String json(String token, int expiresIn) {
        return "{\"access_token\":\"" + token + "\",\"expires_in\":" + expiresIn + "}";
    }

    @Test
    void reutilise_le_jeton_tant_qu_il_n_arrive_pas_a_expiration() {
        ClientCredentialsTokenSource source = source();

        assertThat(source.get()).isEqualTo("jeton-1");
        clock.advanceSeconds(200);
        assertThat(source.get()).isEqualTo("jeton-1");

        assertThat(calls).hasValue(1);
    }

    @Test
    void renouvelle_avant_l_expiration_pour_tenir_une_session_plus_longue_que_le_jeton() {
        ClientCredentialsTokenSource source = source();
        source.get();
        body = json("jeton-2", 300);

        // 300 s de durée, 30 s de marge : à 271 s il ne reste que 29 s, on renouvelle.
        clock.advanceSeconds(271);

        assertThat(source.get()).isEqualTo("jeton-2");
        assertThat(calls).hasValue(2);
    }

    @Test
    void encode_le_formulaire_et_envoie_le_perimetre() {
        source().get();

        assertThat(lastForm.get())
                .contains("grant_type=client_credentials")
                .contains("client_id=kex-supervisor")
                .contains("client_secret=s+e%26cret")
                .contains("scope=openid");
    }

    @Test
    void ne_sert_jamais_un_jeton_perime_quand_le_renouvellement_echoue() {
        ClientCredentialsTokenSource source = source();
        source.get();
        clock.advanceSeconds(400);
        status = 503;

        assertThatThrownBy(source::get).isInstanceOf(IllegalStateException.class);

        status = 200;
        body = json("jeton-3", 300);
        assertThat(source.get()).isEqualTo("jeton-3");
    }

    @Test
    void refuse_une_reponse_sans_access_token() {
        body = "{\"expires_in\":300}";

        assertThatThrownBy(() -> source().get()).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void le_customizer_pose_le_jeton_renouvele_et_ne_le_diffuse_pas_ailleurs() {
        ClientCredentialsTokenSource source = source();
        McpAuthProperties.ClientCredentials oauth =
                new McpAuthProperties.ClientCredentials("http://x/token", "c", "s", null);
        McpBearerTokenCustomizer customizer = new McpBearerTokenCustomizer(
                List.of(new McpAuthProperties.BearerToken("http://autoroutes:8090", null, null, oauth)),
                ignored -> source);

        assertThat(authorization(customizer, "http://autoroutes:8090/mcp")).isEqualTo("Bearer jeton-1");
        assertThat(authorization(customizer, "http://autre:8090/mcp")).isNull();

        body = json("jeton-2", 300);
        clock.advanceSeconds(300);
        assertThat(authorization(customizer, "http://autoroutes:8090/mcp")).isEqualTo("Bearer jeton-2");
    }

    private static String authorization(McpBearerTokenCustomizer customizer, String url) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url));
        customizer.customize(builder, "POST", URI.create(url), "{}", null);
        return builder.GET().build().headers().firstValue("Authorization").orElse(null);
    }

    private static final class MutableClock extends Clock {
        private Instant now;

        MutableClock(Instant now) {
            this.now = now;
        }

        void advanceSeconds(long seconds) {
            now = now.plusSeconds(seconds);
        }

        @Override
        public Instant instant() {
            return now;
        }

        @Override
        public java.time.ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }
    }
}
