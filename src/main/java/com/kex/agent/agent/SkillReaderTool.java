// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors
package com.kex.agent.agent;

import java.util.Map;
import java.util.function.Consumer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.DefaultToolDefinition;
import org.springframework.ai.tool.definition.ToolDefinition;

/**
 * {@code read_skill}, construit pour une seule demande : il ne rend que les compétences que l'index
 * de cette demande liste, sans accès au dépôt. Un autre locataire, une compétence non approuvée ou
 * hors plafond ne sont donc pas atteignables, quel que soit l'identifiant que le modèle invente.
 *
 * <p>Hors du catalogue d'outils et de l'allowlist d'une tâche : il ne lit que ce que le prompt
 * annonce déjà, sans effet ni appel externe. L'exclure d'une tâche à allowlist reviendrait à lui
 * présenter un index qu'elle ne peut pas lire — {@code AgentService} recopie alors les procédures.
 */
public final class SkillReaderTool implements ToolCallback {
    public static final String NAME = "read_skill";

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final ToolDefinition DEFINITION = new DefaultToolDefinition(NAME,
            "Lit le texte d'une compétence approuvée listée dans le contexte durable de cet échange, "
                    + "par son identifiant. À appeler avant d'appliquer la procédure qu'elle décrit.",
            """
            {"type":"object","properties":{"id":{"type":"string","description":"Identifiant listé dans l'index des compétences"}},"required":["id"]}""");

    private final Map<String, String> skills;
    private final Consumer<String> onRead;

    public SkillReaderTool(Map<String, String> skills, Consumer<String> onRead) {
        this.skills = Map.copyOf(skills);
        this.onRead = onRead;
    }

    @Override
    public ToolDefinition getToolDefinition() {
        return DEFINITION;
    }

    @Override
    public String call(String input) {
        return call(input, null);
    }

    @Override
    public String call(String input, ToolContext toolContext) {
        AgentExecution.ensureActive(toolContext);
        return ToolCallRecorder.timed(toolContext, NAME, () -> read(id(input)));
    }

    private String read(String id) {
        String body = id == null ? null : skills.get(id);
        // Une réponse plutôt qu'une exception : le modèle peut se reprendre sur l'identifiant
        // exact de l'index, ce qu'un échec d'outil l'inciterait moins à faire.
        if (body == null) return "Aucune compétence « " + id + " » dans l'index de cet échange.";
        onRead.accept(id);
        return body;
    }

    private static String id(String input) {
        try {
            JsonNode id = JSON.readTree(input == null ? "{}" : input).get("id");
            return id == null || !id.isTextual() ? null : id.asText().strip();
        }
        catch (Exception ex) {
            return null;
        }
    }
}
