// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors
package com.kex.agent.skills;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import com.kex.agent.agent.AgentAnswer;
import com.kex.agent.agent.AgentEvent;
import com.kex.agent.agent.AgentProbe;
import com.kex.agent.agent.AgentService;
import com.kex.agent.memory.LearningEntry;
import com.kex.agent.memory.MemoryIdentity;
import com.kex.agent.memory.MemoryProperties;
import com.kex.agent.memory.SkillProbe;
import com.kex.agent.memory.SkillScope;

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
    /** Au-delà, un écart d'ensemble ne dit plus rien d'exploitable, et le prompt déborde des plafonds. */
    public static final int MAX_TOGETHER = 5;

    private final SkillsService skills;
    private final AgentService agent;
    private final MemoryProperties.Skills limits;
    private final Clock clock;
    private final Set<String> running = ConcurrentHashMap.newKeySet();

    public SkillProbeService(SkillsService skills, AgentService agent, MemoryProperties.Skills limits, Clock clock) {
        this.skills = skills;
        this.agent = agent;
        this.limits = limits;
        this.clock = clock;
    }

    /** Plafonds par défaut : pour les appelants qui ne règlent que l'allowlist du rejeu. */
    public SkillProbeService(SkillsService skills, AgentService agent, MemoryProperties.Probe probe, Clock clock) {
        this(skills, agent, new MemoryProperties.Skills(5, 3, 3000, java.time.Duration.ofDays(90), probe, 12000), clock);
    }

    public LearningEntry probe(String id, String request, int repetitions, String actor) {
        String message = validated(request, repetitions, actor);
        Set<String> tools = tools();
        LearningEntry candidate = skills.pending(id);
        // Un double-clic paierait deux rejeux pour un seul écart utile, et le second écraserait le
        // premier dans l'ordre d'arrivée plutôt que dans celui des demandes.
        if (!running.add(id)) throw new IllegalStateException("Rejeu déjà en cours pour cette compétence");
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<CompletableFuture<SkillProbe.Run>> references = launch(repetitions, executor,
                    () -> run(candidate.owner(), message, tools, null));
            List<CompletableFuture<SkillProbe.Run>> withSkill = launch(repetitions, executor,
                    () -> run(candidate.owner(), message, tools, candidate));
            SkillProbe probe = SkillProbe.of(clock.instant(), actor, message, joined(references), joined(withSkill),
                    candidate.id(), readRequired(candidate), setting(candidate, List.of(candidate), message));
            return skills.attachProbe(id, probe);
        }
        finally {
            running.remove(id);
        }
    }

    /**
     * Rejoue la même demande sans les candidates, puis avec toutes à la fois. Un rejeu seul ne voit
     * ni ce que deux procédures font l'une à l'autre, ni qu'approuvées ensemble elles dépasseraient
     * un plafond et en évinceraient une — ou une approuvée. Le coût reste {@code 2 × repetitions}
     * échanges, quel que soit leur nombre.
     *
     * <p>L'écart est joint à chacune, qualifié {@code CONFOUNDED} : mesuré à plusieurs, il ne
     * s'attribue à aucune. Il ne remplace donc pas le rejeu seul, il le complète.
     *
     * @return les compétences auxquelles le rejeu a été joint ; une tranchée entre-temps est sautée
     */
    public List<LearningEntry> probeTogether(List<String> ids, String request, int repetitions, String actor) {
        String message = validated(request, repetitions, actor);
        List<String> distinct = ids == null ? List.of() : ids.stream().distinct().toList();
        if (distinct.size() < 2 || distinct.size() > MAX_TOGETHER) {
            throw new IllegalArgumentException("De 2 à " + MAX_TOGETHER + " compétences distinctes à rejouer ensemble");
        }
        Set<String> tools = tools();
        List<LearningEntry> candidates = distinct.stream().map(skills::pending).toList();
        // Le contexte durable est celui d'un propriétaire : mêler deux bibliothèques mesurerait un
        // prompt que personne ne reçoit jamais.
        String owner = candidates.getFirst().owner();
        if (candidates.stream().anyMatch(candidate -> !candidate.owner().equals(owner))) {
            throw new IllegalArgumentException("Les compétences rejouées ensemble doivent appartenir au même propriétaire");
        }
        List<String> claimed = new ArrayList<>();
        try {
            for (String id : distinct) {
                if (!running.add(id)) throw new IllegalStateException("Rejeu déjà en cours pour une de ces compétences");
                claimed.add(id);
            }
            try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
                List<CompletableFuture<SkillProbe.Run>> references = launch(repetitions, executor,
                        () -> runTogether(owner, message, tools, List.of()));
                List<CompletableFuture<SkillProbe.Run>> together = launch(repetitions, executor,
                        () -> runTogether(owner, message, tools, candidates));
                List<SkillProbe.Run> referenceRuns = joined(references);
                List<SkillProbe.Run> togetherRuns = joined(together);
                List<LearningEntry> attached = new ArrayList<>();
                for (LearningEntry candidate : candidates) {
                    SkillProbe probe = SkillProbe.of(clock.instant(), actor, message, referenceRuns, togetherRuns,
                            candidate.id(), readRequired(candidate), setting(candidate, candidates, message));
                    try {
                        attached.add(skills.attachProbe(candidate.id(), probe));
                    }
                    catch (IllegalStateException ex) {
                        // Tranchée pendant le rejeu : la revue l'emporte, comme pour un rejeu seul.
                    }
                }
                if (attached.isEmpty()) throw new IllegalStateException("Compétences examinées pendant le rejeu ; rejeu non conservé");
                return attached;
            }
        }
        finally {
            claimed.forEach(running::remove);
        }
    }

    /**
     * Ce que le prompt contient de part et d'autre, calculé par la sélection même du contexte
     * durable : il dépend des plafonds et des déclencheurs, pas du modèle.
     */
    private SkillProbe.Setting setting(LearningEntry subject, List<LearningEntry> candidates, String message) {
        String owner = subject.owner();
        List<LearningEntry> without = skills.injectable(owner, message, List.of(), limits);
        List<LearningEntry> with = skills.injectable(owner, message, candidates, limits);
        Set<String> injected = with.stream().map(LearningEntry::id).collect(Collectors.toSet());
        Set<String> candidateIds = candidates.stream().map(LearningEntry::id).collect(Collectors.toSet());
        List<String> companions = candidates.stream().filter(candidate -> !candidate.id().equals(subject.id()))
                .map(LearningEntry::title).toList();
        List<String> evicted = candidates.stream().filter(candidate -> !injected.contains(candidate.id()))
                .map(LearningEntry::title).toList();
        List<String> displaced = without.stream()
                .filter(entry -> !candidateIds.contains(entry.id()) && !injected.contains(entry.id()))
                .map(LearningEntry::title).toList();
        return new SkillProbe.Setting(companions, evicted, displaced, !injected.contains(subject.id()));
    }

    /**
     * Sous rejeu, l'allowlist n'est jamais vide : une compétence de domaine n'y est qu'indexée et
     * n'agit qu'une fois lue ; une invariante est recopiée en entier.
     */
    private static boolean readRequired(LearningEntry candidate) {
        return candidate.effectiveScope() == SkillScope.DOMAIN;
    }

    private static String validated(String request, int repetitions, String actor) {
        MemoryIdentity.require(actor);
        if (repetitions < 1 || repetitions > MAX_REPETITIONS) {
            throw new IllegalArgumentException("Répétitions de 1 à " + MAX_REPETITIONS);
        }
        if (request == null || request.isBlank() || request.length() > 4000) {
            throw new IllegalArgumentException("Demande à rejouer requise et limitée à 4000 caractères");
        }
        return request.strip();
    }

    private Set<String> tools() {
        Set<String> tools = limits.probe().readOnlyTools();
        if (tools.isEmpty()) {
            throw new IllegalStateException(
                    "Rejeu désactivé : kex.agent.memory.skills.probe.read-only-tools est vide");
        }
        return tools;
    }

    private static List<SkillProbe.Run> joined(List<CompletableFuture<SkillProbe.Run>> runs) {
        return runs.stream().map(CompletableFuture::join).toList();
    }

    private static List<CompletableFuture<SkillProbe.Run>> launch(int repetitions, ExecutorService executor,
                                                                   java.util.function.Supplier<SkillProbe.Run> run) {
        return IntStream.range(0, repetitions).mapToObj(i -> CompletableFuture.supplyAsync(run, executor)).toList();
    }

    private SkillProbe.Run run(String owner, String message, Set<String> tools, LearningEntry candidate) {
        return measured(() -> agent.probe(owner, message, tools, candidate));
    }

    private SkillProbe.Run runTogether(String owner, String message, Set<String> tools, List<LearningEntry> candidates) {
        return measured(() -> agent.probeTogether(owner, message, tools, candidates));
    }

    private static SkillProbe.Run measured(java.util.function.Supplier<AgentProbe> call) {
        try {
            AgentProbe probe = call.get();
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
