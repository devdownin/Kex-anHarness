// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors
package com.kex.agent.supervision.eval;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;

import com.fasterxml.jackson.core.type.TypeReference;
import com.kex.agent.memory.SkillScope;

/**
 * The harness under evaluation besides the model: approved skills seeded before the first trial.
 * Same model, two fixtures, two reports — the comparison then isolates what the skills change.
 */
public record AgentEvalHarness(String label, String fingerprint, List<Skill> skills) {
    public static final AgentEvalHarness BASE = new AgentEvalHarness("base", "none", List.of());

    public AgentEvalHarness {
        skills = List.copyOf(skills);
    }

    public record Skill(String title, String markdown, SkillScope scope, List<String> triggers) {
        public Skill {
            triggers = triggers == null ? List.of() : List.copyOf(triggers);
        }
    }

    public static AgentEvalHarness load(Path file) throws IOException {
        byte[] content = Files.readAllBytes(file);
        List<Skill> skills = AgentEvalCorpus.JSON.readValue(content, new TypeReference<>() { });
        if (skills.isEmpty() || skills.stream().anyMatch(skill -> skill.title() == null || skill.title().isBlank()
                || skill.markdown() == null || skill.markdown().isBlank())) {
            throw new IOException("Invalid evaluation harness: " + file);
        }
        return new AgentEvalHarness(file.getFileName().toString(), sha256(content), skills);
    }

    private static String sha256(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        }
        catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
    }
}
