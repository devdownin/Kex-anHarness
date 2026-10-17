// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors
package com.kex.agent.supervision;

/**
 * Un fait relevé, pas une opinion : « 15 messages en attente ». Affiché avant toute analyse.
 *
 * @param tool l'outil dont le résultat porte la mesure, tel que le modèle le cite ; {@code null}
 *             pour une observation antérieure à la citation, ou recopiée d'une décision
 */
public record Observation(String label, String value, String tool) {
    public Observation(String label, String value) {
        this(label, value, null);
    }
}
