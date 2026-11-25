// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors
package com.kex.agent.supervision;

/** Les actions que l'agent peut recommander. Chacune se lie à un outil MCP par configuration. */
public enum Capability {

    NOTIFY,
    CREATE_INCIDENT,
    RESTART_CONSUMER,
    REPLAY_MESSAGES,
    MODIFY_CONFIGURATION,
    /** Déclare l'état d'une source sur un système tiers (anomalie de gare). Arguments proposés, jamais automatique. */
    DECLARE_ANOMALY,
    /** Lève ce qu'une source avait déclaré sur un système tiers. Même régime que {@link #DECLARE_ANOMALY}. */
    CLEAR_ANOMALY
}
