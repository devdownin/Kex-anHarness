// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors
package com.kex.agent.agent;

import java.util.List;

/**
 * Une exécution de rejeu et les compétences qu'elle a lues. Savoir que la candidate n'a jamais été
 * lue change la lecture de l'écart : sans lecture, un écart tient au hasard, pas à la procédure.
 */
public record AgentProbe(AgentAnswer answer, List<String> skillsRead) {
    public AgentProbe {
        skillsRead = List.copyOf(skillsRead);
    }
}
