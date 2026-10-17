// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors
package com.kex.agent.skills;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kex.agent.agent.AgentAnswer;
import com.kex.agent.agent.AgentEvent;
import com.kex.agent.agent.AgentProbe;
import com.kex.agent.agent.AgentService;
import com.kex.agent.agent.AgentTimeoutException;
import com.kex.agent.agent.AgentUsage;
import com.kex.agent.memory.FileLearningRepository;
import com.kex.agent.memory.LearningEntry;
import com.kex.agent.memory.MemoryProperties;
import com.kex.agent.memory.SkillProbe;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.ArgumentMatchers.notNull;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class SkillProbeServiceTest {

    private static final Set<String> TOOLS = Set.of("kex_consumer_lag", "kex_diagnose_consumer");
    private static final Instant NOW = Instant.parse("2026-10-01T12:00:00Z");

    @TempDir
    Path directory;

    private final AgentService agent = mock(AgentService.class);
    private SkillsService skills;
    private LearningEntry pending;

    @BeforeEach
    void setUp() {
        skills = new SkillsService(new FileLearningRepository(new ObjectMapper().findAndRegisterModules(),
                directory.resolve("learning.json"), 50), Clock.fixed(NOW, ZoneId.of("UTC")));
        pending = skills.propose("ops-console", "Lag", "Toujours lire la tendance", "Preuve", null);
    }

    @Test
    void le_rejeu_joint_l_ecart_entre_reference_et_candidate_sans_verdict() {
        given(agent.probe(eq("ops-console"), eq("Le lag de billing ?"), eq(TOOLS), isNull()))
                .willReturn(answer(List.of("kex_consumer_lag"), new AgentUsage(100, 20)));
        given(agent.probe(eq("ops-console"), eq("Le lag de billing ?"), eq(TOOLS), notNull()))
                .willReturn(answer(List.of("kex_consumer_lag", "kex_diagnose_consumer"), null));

        LearningEntry probed = service(TOOLS).probe(pending.id(), "  Le lag de billing ?  ", 1, "admin");

        SkillProbe probe = probed.probes().getFirst();
        assertThat(probe.actor()).isEqualTo("admin");
        assertThat(probe.observedAt()).isEqualTo(NOW);
        assertThat(probe.toolsAdded()).containsExactly("kex_diagnose_consumer");
        assertThat(probe.toolsDropped()).isEmpty();
        assertThat(probe.references().getFirst().inputTokens()).isEqualTo(100);
        // Le fournisseur n'a rien dit : une absence, jamais zéro.
        assertThat(probe.candidates().getFirst().inputTokens()).isNull();
        assertThat(skills.pending(pending.id()).probes()).containsExactly(probe);
        verify(agent).probe(eq("ops-console"), any(), eq(TOOLS),
                org.mockito.ArgumentMatchers.argThat(entry -> entry != null && entry.id().equals(pending.id())));
    }

    @Test
    void les_deux_executions_partent_en_meme_temps() {
        CountDownLatch both = new CountDownLatch(2);
        given(agent.probe(any(), any(), any(), any())).willAnswer(invocation -> {
            both.countDown();
            // Une exécution séquentielle attendrait ici l'autre, qui ne partirait jamais.
            assertThat(both.await(5, TimeUnit.SECONDS)).isTrue();
            return answer(List.of(), null);
        });

        LearningEntry probed = service(TOOLS).probe(pending.id(), "lag ?", 1, "admin");

        assertThat(probed.probes().getFirst().references().getFirst().error()).isNull();
        assertThat(probed.probes().getFirst().candidates().getFirst().error()).isNull();
    }

    @Test
    void un_echec_d_execution_est_un_ecart_et_ne_recopie_pas_le_message() {
        given(agent.probe(any(), any(), any(), isNull())).willReturn(answer(List.of("kex_consumer_lag"), null));
        given(agent.probe(any(), any(), any(), notNull()))
                .willThrow(new IllegalStateException("{\"error\":\"corps du fournisseur\"}"));

        SkillProbe probe = service(TOOLS).probe(pending.id(), "lag ?", 1, "admin").probes().getFirst();

        assertThat(probe.candidates().getFirst().error()).isEqualTo("IllegalStateException");
        assertThat(probe.candidates().getFirst().answer()).isNull();
        assertThat(probe.toolsDropped()).containsExactly("kex_consumer_lag");
    }

    @Test
    void un_delai_depasse_reste_un_ecart() {
        given(agent.probe(any(), any(), any(), isNull())).willReturn(answer(List.of(), null));
        given(agent.probe(any(), any(), any(), notNull()))
                .willThrow(new AgentTimeoutException(java.time.Duration.ofSeconds(1), "c"));

        SkillProbe probe = service(TOOLS).probe(pending.id(), "lag ?", 1, "admin").probes().getFirst();

        assertThat(probe.candidates().getFirst().error()).isEqualTo("AgentTimeoutException");
    }

    @Test
    void sans_allowlist_configuree_le_rejeu_est_eteint_et_n_appelle_rien() {
        assertThatThrownBy(() -> service(Set.of()).probe(pending.id(), "lag ?", 1, "admin"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("read-only-tools");
        verify(agent, never()).probe(any(), any(), any(), any());
    }

    @Test
    void une_demande_vide_ou_une_competence_deja_tranchee_est_refusee() {
        assertThatThrownBy(() -> service(TOOLS).probe(pending.id(), " ", 1, "admin"))
                .isInstanceOf(IllegalArgumentException.class);
        skills.review(pending.id(), true, "admin", "ok");
        assertThatThrownBy(() -> service(TOOLS).probe(pending.id(), "lag ?", 1, "admin"))
                .isInstanceOf(IllegalStateException.class);
        verify(agent, never()).probe(any(), any(), any(), any());
    }

    @Test
    void une_revue_tranchee_pendant_le_rejeu_l_emporte() {
        given(agent.probe(any(), any(), any(), any())).willAnswer(invocation -> {
            if (invocation.getArgument(3) != null) skills.review(pending.id(), false, "admin", "non");
            return answer(List.of(), null);
        });

        assertThatThrownBy(() -> service(TOOLS).probe(pending.id(), "lag ?", 1, "admin"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("pendant le rejeu");
        assertThat(skills.list("ops-console").getFirst().probes()).isEmpty();
    }

    @Test
    void un_second_rejeu_simultane_est_refuse() {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        given(agent.probe(any(), any(), any(), any())).willAnswer(invocation -> {
            started.countDown();
            release.await(5, TimeUnit.SECONDS);
            return answer(List.of(), null);
        });
        SkillProbeService service = service(TOOLS);
        Thread first = Thread.startVirtualThread(() -> service.probe(pending.id(), "lag ?", 1, "admin"));
        try {
            assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
            assertThatThrownBy(() -> service.probe(pending.id(), "lag ?", 1, "admin"))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("déjà en cours");
        }
        catch (InterruptedException ex) {
            throw new AssertionError(ex);
        }
        finally {
            release.countDown();
        }
        try {
            first.join();
        }
        catch (InterruptedException ex) {
            throw new AssertionError(ex);
        }
        assertThat(skills.pending(pending.id()).probes()).hasSize(1);
    }

    @Test
    void trois_passes_de_chaque_cote_ne_retiennent_que_l_ecart_stable() {
        CountDownLatch all = new CountDownLatch(6);
        java.util.concurrent.atomic.AtomicInteger candidateRuns = new java.util.concurrent.atomic.AtomicInteger();
        given(agent.probe(any(), any(), any(), any())).willAnswer(invocation -> {
            all.countDown();
            // Les six exécutions partent ensemble : séquentielles, elles ne passeraient jamais ce point.
            assertThat(all.await(5, TimeUnit.SECONDS)).isTrue();
            if (invocation.getArgument(3) == null) return answer(List.of("kex_consumer_lag"), null);
            boolean first = candidateRuns.getAndIncrement() == 0;
            // Le diagnostic n'apparaît qu'une fois sur trois : un écart de hasard, pas de la compétence.
            return answer(first ? List.of("kex_consumer_lag", "kex_lag_trend", "kex_diagnose_consumer")
                    : List.of("kex_consumer_lag", "kex_lag_trend"), null, List.of(pending.id()));
        });

        SkillProbe probe = service(TOOLS).probe(pending.id(), "lag ?", 3, "admin").probes().getFirst();

        assertThat(probe.references()).hasSize(3);
        assertThat(probe.candidates()).hasSize(3);
        assertThat(probe.toolsAdded()).containsExactly("kex_lag_trend");
        assertThat(probe.toolsDropped()).isEmpty();
        assertThat(probe.tools()).contains(new SkillProbe.ToolFrequency("kex_diagnose_consumer", 0, 1),
                new SkillProbe.ToolFrequency("kex_consumer_lag", 3, 3));
        assertThat(probe.candidates()).allSatisfy(run -> assertThat(run.skillsRead()).containsExactly(pending.id()));
    }

    @Test
    void les_repetitions_sont_bornees() {
        assertThatThrownBy(() -> service(TOOLS).probe(pending.id(), "lag ?", 0, "admin"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service(TOOLS).probe(pending.id(), "lag ?", 4, "admin"))
                .isInstanceOf(IllegalArgumentException.class);
        verify(agent, never()).probe(any(), any(), any(), any());
    }

    private SkillProbeService service(Set<String> tools) {
        return new SkillProbeService(skills, agent, new MemoryProperties.Probe(tools), Clock.fixed(NOW, ZoneId.of("UTC")));
    }

    private static AgentProbe answer(List<String> tools, AgentUsage usage) {
        return answer(tools, usage, List.of());
    }

    private static AgentProbe answer(List<String> tools, AgentUsage usage, List<String> skillsRead) {
        List<AgentEvent.ToolCall> calls = tools.stream()
                .map(tool -> new AgentEvent.ToolCall(tool, 5, false, NOW.toString(), null)).toList();
        return new AgentProbe(new AgentAnswer("probe", "Réponse", calls, usage, "end_turn"), skillsRead);
    }
}
