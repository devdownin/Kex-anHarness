// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors
package com.kex.agent.config;

/** Niveau d'accès d'une clé API nommée. */
public enum ApiRole {
    CHAT,
    OPERATOR,
    ADMIN,
    /**
     * Lecture de {@code /actuator/prometheus} et rien d'autre : un collecteur n'avait jusqu'ici que
     * la clé {@code ADMIN} pour lire les métriques, et la garder dans sa configuration lui donnait
     * aussi le droit de changer la politique de supervision.
     */
    METRICS
}
