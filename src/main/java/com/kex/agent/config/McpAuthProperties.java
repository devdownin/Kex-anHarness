// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors
package com.kex.agent.config;

import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** Jetons à injecter sur les transports MCP HTTP, par préfixe d'URL. */
@ConfigurationProperties("kex.mcp")
public record McpAuthProperties(@DefaultValue List<BearerToken> bearerTokens) {

    /**
     * @param header en-tête qui porte le jeton tel quel ; absent, {@code Authorization: Bearer}.
     *               Tous les serveurs n'attendent pas un porteur : SpectraLLM lit {@code X-API-Key}
     *               et répond 401 à un {@code Authorization} qu'il ne regarde pas.
     */
    public record BearerToken(String urlPrefix, String token, String header) {
    }
}
