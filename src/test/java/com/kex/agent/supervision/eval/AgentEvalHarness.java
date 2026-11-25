// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors
package com.kex.agent.supervision.eval;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

import com.fasterxml.jackson.core.type.TypeReference;
import com.kex.agent.memory.SkillScope;

/**
 * Ce qui est évalué autour du modèle : des compétences approuvées semées avant le premier essai, et
 * les options du harnais allumées pour l'exécution. Même modèle, deux harnais, deux rapports : la
 * comparaison isole ce que l'un change par rapport à l'autre — réussite, appels d'outils, jetons.
 *
 * <p>Les options du harnais (compaction, résultats déchargés, découverte, sélection d'outils) sont
 * éteintes par défaut, faute d'avoir jamais été mesurées. Ceci permet de les mesurer avant de
 * changer un défaut, plutôt que de le changer sur la foi d'un chiffre lu ailleurs.
 */
public record AgentEvalHarness(String label, String fingerprint, List<Skill> skills, List<String> options) {
    public static final AgentEvalHarness BASE = new AgentEvalHarness("base", "none", List.of(), List.of());

    /** Option de harnais → propriétés qui l'allument. L'ordre fixe celui du libellé du rapport. */
    public static final Map<String, Map<String, String>> OPTIONS = knownOptions();

    public AgentEvalHarness {
        skills = List.copyOf(skills);
        options = List.copyOf(options);
    }

    private static Map<String, Map<String, String>> knownOptions() {
        Map<String, Map<String, String>> options = new LinkedHashMap<>();
        options.put("compaction", Map.of("kex.agent.harness.compaction.enabled", "true"));
        options.put("results", Map.of("kex.agent.harness.results.enabled", "true"));
        options.put("discovery", Map.of("kex.agent.harness.discovery.enabled", "true"));
        options.put("selection", Map.of("kex.agent.tools.selection-enabled", "true"));
        return Map.copyOf(options);
    }

    /**
     * Le même harnais avec les options listées (séparées par des virgules) allumées. Une option
     * inconnue fait échouer l'exécution avant tout appel payant : mal orthographiée, elle aurait
     * produit un rapport « avec compaction » mesuré sans elle.
     */
    public AgentEvalHarness withOptions(String requested) {
        TreeSet<String> chosen = new TreeSet<>();
        if (requested != null) {
            for (String option : requested.split(",")) {
                String name = option.strip().toLowerCase(java.util.Locale.ROOT);
                if (name.isEmpty()) continue;
                if (!OPTIONS.containsKey(name)) {
                    throw new IllegalArgumentException("Option de harnais inconnue : " + name + " (connues : "
                            + String.join(", ", new TreeSet<>(OPTIONS.keySet())) + ")");
                }
                chosen.add(name);
            }
        }
        if (chosen.isEmpty()) return this;
        String suffix = String.join(",", chosen);
        return new AgentEvalHarness(label + "+" + suffix, sha256((fingerprint + "|" + suffix).getBytes(
                java.nio.charset.StandardCharsets.UTF_8)), skills, List.copyOf(chosen));
    }

    /** Les propriétés Spring que les options choisies posent pour l'exécution. */
    public Map<String, String> properties() {
        Map<String, String> properties = new LinkedHashMap<>();
        options.forEach(option -> properties.putAll(OPTIONS.get(option)));
        return properties;
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
        return new AgentEvalHarness(file.getFileName().toString(), sha256(content), skills, List.of());
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
