// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors
package com.kex.agent.supervision;

import java.net.URI;

/**
 * Un lien vers l'application qui montre ce processus de près — un topic dans Kafka SQL Explorer,
 * une carte. Déclaré par l'exploitant avec le processus : l'agent ne connaît pas les routes des
 * autres applications, et en deviner une ferait ouvrir une page 404 au moment où l'on enquête.
 *
 * <p>Seuls {@code http} et {@code https} sont acceptés : un {@code javascript:} servi dans la
 * console s'exécuterait au clic.
 */
public record ProcessLink(String label, String url) {

    public ProcessLink {
        if (label == null || label.isBlank()) {
            throw new IllegalArgumentException("Un lien de processus exige un libellé");
        }
        URI uri;
        try {
            uri = URI.create(url == null ? "" : url.strip());
        }
        catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("Lien de processus invalide : " + url, ex);
        }
        if (!("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))
                || uri.getHost() == null) {
            throw new IllegalArgumentException("Un lien de processus doit être une URL http(s) absolue : " + url);
        }
        label = label.strip();
        url = uri.toString();
    }
}
