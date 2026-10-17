// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors
package com.kex.agent.skills;

import java.time.Clock;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.IntStream;

import com.kex.agent.agent.AgentAnswer;
import com.kex.agent.agent.AgentEvent;
import com.kex.agent.agent.AgentProbe;
import com.kex.agent.agent.AgentService;
import com.kex.agent.memory.LearningEntry;
import com.kex.agent.memory.MemoryIdentity;
import com.kex.agent.memory.MemoryProperties;
import com.kex.agent.memory.SkillProbe;

/**
 * Rejoue une demande avec et sans une compétence en attente, et joint l'écart à la compétence
 * avant qu'un humain ne tranche. Sans lui, le réviseur approuvait sur la seule preuve textuelle
 * de la proposition, sans savoir si la procédure changeait quoi que ce soit au comportement.
 *
 * <p>Toutes les exécutions partent en même temps : elles lisent ainsi le même état du cluster, ce
 * qu'un rejeu séquentiel ne garantit pas — un lag qui bouge entre deux passes passerait pour un
 * effet de la compétence. Répéter de chaque côté sépare un écart stable du hasard d'un seul essai. Elles restent sous l'allowlist de configuration : le rejeu appelle réellement
 * les outils, et le serveur de Kafka SQL Explorer n'est en lecture seule que tant qu'on n'en
 * branche pas d'autre.
 */
public final class SkillProbeService {
    private static final int ANSWER_LIMIT = 2000;
    public static final int MAX_REPETITIONS = 3;

    private final SkillsService skills;
    private final AgentService agent;
    private final MemoryProperties.Probe properties;
    private final Clock clock;
    private final Set<String> running = ConcurrentHashMap.newKeySet();

    public SkillProbeService(SkillsService skills, AgentService agent, MemoryProperties.Probe properties, Clock clock) {
        this.skills = skills;
        this.agent = agent;
        this.properties = properties;
        this.clock = clock;
    }

    public LearningEntry probe(String id, String request, int repetitions, String actor) {
        MemoryIdentity.require(actor);
        if (repetitions < 1 || repetitions > MAX_REPETITIONS) {
            throw new IllegalArgumentException("Répétitions de 1 à " + MAX_REPETITIONS);
        }
        if (request == null || request.isBlank() || request.length() > 4000) {
            throw new IllegalArgumentException("Demande à rejouer requise et limitée à 4000 caractères");
        }
        Set<String> tools = properties.readOnlyTools();
        if (tools.isEmpty()) {
            throw new IllegalStateException(
                    "Rejeu désactivé : kex.agent.memory.skills.probe.read-only-tools est vide");
        }
        LearningEntry candidate = skills.pending(id);
        // Un double-clic paierait deux rejeux pour un seul écart utile, et le second écraserait le
        // premier dans l'ordre d'arrivée plutôt que dans celui des demandes.
        if (!running.add(id)) throw new IllegalStateException("Rejeu déjà en cours pour cette compétence");
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            String message = request.strip();
            List<CompletableFuture<SkillProbe.Run>> references = launch(repetitions, executor,
                    () -> run(candidate.owner(), message, tools, null));
            List<CompletableFuture<SkillProbe.Run>> withSkill = launch(repetitions, executor,
                    () -> run(candidate.owner(), message, tools, candidate));
            SkillProbe probe = SkillProbe.of(clock.instant(), actor, message,
                    references.stream().map(CompletableFuture::join).toList(),
                    withSkill.stream().map(CompletableFuture::join).toList());
            return skills.attachProbe(id, probe);
        }
        finally {
            running.remove(id);
        }
    }

    private static List<CompletableFuture<SkillProbe.Run>> launch(int repetitions, ExecutorService executor,
                                                                   java.util.function.Supplier<SkillProbe.Run> run) {
        return IntStream.range(0, repetitions).mapToObj(i -> CompletableFuture.supplyAsync(run, executor)).toList();
    }

    private SkillProbe.Run run(String owner, String message, Set<String> tools, LearningEntry candidate) {
        try {
            AgentProbe probe = agent.probe(owner, message, tools, candidate);
            AgentAnswer answer = probe.answer();
            List<AgentEvent.ToolCall> calls = answer.tools();
            return new SkillProbe.Run(calls.stream().map(AgentEvent.ToolCall::tool).toList(),
                    (int) calls.stream().filter(AgentEvent.ToolCall::failed).count(),
                    answer.usage() == null ? null : answer.usage().inputTokens(),
                    answer.usage() == null ? null : answer.usage().outputTokens(),
                    answer.finishReason(), bounded(answer.content()), null, probe.skillsRead());
        }
        catch (RuntimeException ex) {
            // Une exécution en échec est un écart comme un autre : la compétence qui fait échouer
            // l'agent là où la référence aboutit est précisément ce que le réviseur doit voir.
            return SkillProbe.Run.failed(ex.getClass().getSimpleName());
        }
    }

    private static String bounded(String value) {
        if (value == null) return null;
        String stripped = value.strip();
        return stripped.length() <= ANSWER_LIMIT ? stripped : stripped.substring(0, ANSWER_LIMIT);
    }
}
