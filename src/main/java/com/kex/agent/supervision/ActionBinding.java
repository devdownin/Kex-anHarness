// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors
package com.kex.agent.supervision;

import java.util.List;
import java.util.Map;

import org.springframework.boot.context.properties.bind.ConstructorBinding;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Outil MCP lié à une capacité. Sans liaison, la capacité reste recommandable mais inexécutable —
 * et c'est l'état par défaut : un agent n'hérite pas du droit d'agir d'une installation.
 *
 * @param arguments arguments fixes ; {@code processId} et {@code decisionId} y sont ajoutés à
 *                  l'appel pour que l'outil sache sur quoi il agit
 * @param allowedArguments noms des arguments que le modèle peut proposer pour cette action. Vide par
 *                  défaut : rien de ce que le modèle écrit n'atteint l'outil. Un argument fixe ou
 *                  {@code processId}/{@code decisionId} l'emporte toujours sur une proposition. Une
 *                  décision qui en porte n'est jamais exécutée sans validation humaine.
 */
public record ActionBinding(String connection, String tool, @DefaultValue Map<String, Object> arguments,
                            VerificationBinding verification, @DefaultValue List<String> allowedArguments) {

    @ConstructorBinding
    public ActionBinding {
        arguments = arguments == null ? Map.of() : Map.copyOf(arguments);
        allowedArguments = allowedArguments == null ? List.of() : List.copyOf(allowedArguments);
    }

    public ActionBinding(String connection, String tool, Map<String, Object> arguments,
                         VerificationBinding verification) {
        this(connection, tool, arguments, verification, List.of());
    }

    /** Existing configurations and callers remain executable, explicitly without outcome proof. */
    public ActionBinding(String connection, String tool, Map<String, Object> arguments) {
        this(connection, tool, arguments, null);
    }
}
