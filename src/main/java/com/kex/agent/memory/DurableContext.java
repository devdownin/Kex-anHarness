// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors
package com.kex.agent.memory;

import java.util.Map;

/**
 * Le bloc de contexte durable d'une demande, et les compétences qu'il indexe sans les recopier.
 *
 * @param skills le texte de chaque compétence de domaine listée dans l'index, par identifiant :
 *               ce que {@code read_skill} peut rendre pour cette demande, et rien d'autre
 */
public record DurableContext(String text, Map<String, String> skills) {
    public static final DurableContext EMPTY = new DurableContext("", Map.of());

    public DurableContext {
        skills = Map.copyOf(skills);
    }
}
