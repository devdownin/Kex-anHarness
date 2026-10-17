// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors
package com.kex.agent.skills;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Set;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kex.agent.memory.FileLearningRepository;
import com.kex.agent.memory.LearningEntry;
import com.kex.agent.memory.MemoryProperties;
import com.kex.agent.memory.SkillProbe;
import com.kex.agent.memory.SkillScope;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SkillScopeSelectionTest {

    private static final MemoryProperties.Skills LIMITS =
            new MemoryProperties.Skills(2, 1, 3000, Duration.ofDays(90), new MemoryProperties.Probe(Set.of()));

    @TempDir
    Path directory;

    private Instant now = Instant.parse("2026-10-01T12:00:00Z");
    private FileLearningRepository repository;
    private SkillsService service;

    @BeforeEach
    void setUp() {
        repository = new FileLearningRepository(new ObjectMapper().findAndRegisterModules(),
                directory.resolve("learning.json"), 50);
        service = new SkillsService(repository, Clock.fixed(now, ZoneId.of("UTC")));
    }

    @Test
    void une_procedure_de_domaine_recente_n_evince_plus_une_invariante_ancienne() {
        approved("Citer la couverture", SkillScope.INVARIANT, List.of(), 10);
        approved("Lag A", SkillScope.DOMAIN, List.of(), 3);
        approved("Lag B", SkillScope.DOMAIN, List.of(), 2);
        approved("Lag C", SkillScope.DOMAIN, List.of(), 1);

        assertThat(titles(service.injectable("ops", "bonjour", null, LIMITS)))
                .containsExactly("Citer la couverture", "Lag C", "Lag B");
    }

    @Test
    void une_competence_a_declencheur_n_agit_que_sur_son_sujet_et_passe_alors_devant() {
        approved("DLQ", SkillScope.DOMAIN, List.of("dlq", "Lettre morte"), 5);
        approved("Générale 1", SkillScope.DOMAIN, List.of(), 2);
        approved("Générale 2", SkillScope.DOMAIN, List.of(), 1);

        assertThat(titles(service.injectable("ops", "Quel est le lag ?", null, LIMITS)))
                .containsExactly("Générale 2", "Générale 1");
        assertThat(titles(service.injectable("ops", "Encore une LETTRE MORTE ce matin", null, LIMITS)))
                .containsExactly("DLQ", "Générale 2");
        assertThat(titles(service.injectable("ops", null, null, LIMITS)))
                .containsExactly("Générale 2", "Générale 1");
    }

    @Test
    void un_declencheur_commence_un_mot_et_ignore_les_accents() {
        assertThat(SkillsService.matches(SkillsService.normalize("Les consumers de billing"), List.of("consumer"))).isTrue();
        assertThat(SkillsService.matches(SkillsService.normalize("un flag de config"), List.of("lag"))).isFalse();
        assertThat(SkillsService.matches(SkillsService.normalize("Politique de RÉTENTION"), List.of("retention"))).isTrue();
    }

    @Test
    void une_competence_anterieure_sans_portee_garde_l_injection_d_avant() {
        repository.add(new LearningEntry("legacy", "ops", "SKILL", "Ancienne", "Procédure", "Preuve", null,
                now.minusSeconds(60), "APPROVED", "admin", now.minusSeconds(30), "ok"));

        LearningEntry legacy = service.list("ops").getFirst();
        assertThat(legacy.scope()).isNull();
        assertThat(legacy.effectiveScope()).isEqualTo(SkillScope.DOMAIN);
        assertThat(titles(service.injectable("ops", "n'importe quoi", null, LIMITS))).containsExactly("Ancienne");
    }

    @Test
    void la_candidate_d_un_rejeu_compte_comme_venant_d_etre_approuvee() {
        approved("Lag A", SkillScope.DOMAIN, List.of(), 2);
        approved("Lag B", SkillScope.DOMAIN, List.of(), 1);
        LearningEntry candidate = service.propose("ops", "Candidate", "Procédure", "Preuve", null, null,
                SkillScope.DOMAIN, List.of());

        // Le plafond de deux évince Lag A : c'est exactement ce que l'approbation ferait.
        assertThat(titles(service.injectable("ops", "lag", candidate, LIMITS)))
                .containsExactly("Candidate", "Lag B");
    }

    @Test
    void une_invariante_refuse_un_declencheur_et_les_declencheurs_sont_bornes() {
        assertThatThrownBy(() -> service.propose("ops", "T", "M", "E", null, null, SkillScope.INVARIANT, List.of("lag")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.propose("ops", "T", "M", "E", null, null, SkillScope.DOMAIN, List.of("x")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.propose("ops", "T", "M", "E", null, null, SkillScope.DOMAIN,
                List.of("a1", "a2", "a3", "a4", "a5", "a6", "a7", "a8", "a9", "a10", "a11")))
                .isInstanceOf(IllegalArgumentException.class);

        LearningEntry entry = service.propose("ops", "T", "M", "E", null, null, null,
                java.util.Arrays.asList(" Lag ", "lag", null, "  "));
        assertThat(entry.scope()).isEqualTo(SkillScope.DOMAIN);
        assertThat(entry.triggers()).containsExactly("lag");
    }

    @Test
    void un_rejeu_ne_s_attache_qu_a_une_competence_en_attente_et_les_cinq_derniers_restent() {
        LearningEntry entry = service.propose("ops", "T", "M", "E", null);
        for (int i = 0; i < 7; i++) {
            service.attachProbe(entry.id(), probe("demande " + i));
        }
        assertThat(service.pending(entry.id()).probes()).extracting(SkillProbe::request)
                .containsExactly("demande 2", "demande 3", "demande 4", "demande 5", "demande 6");

        service.review(entry.id(), false, "admin", "non");
        assertThatThrownBy(() -> service.attachProbe(entry.id(), probe("après")))
                .isInstanceOf(IllegalStateException.class);
    }

    private void approved(String title, SkillScope scope, List<String> triggers, int minutesAgo) {
        LearningEntry entry = new LearningEntry(title, "ops", "SKILL", title, "Procédure " + title, "Preuve",
                null, now.minusSeconds(3600), "APPROVED", "admin", now.minusSeconds(60L * minutesAgo), "ok",
                null, scope, triggers, List.of());
        repository.add(entry);
    }

    private SkillProbe probe(String request) {
        SkillProbe.Run run = new SkillProbe.Run(List.of(), 0, null, null, null, "ok", null, List.of());
        return SkillProbe.of(now, "admin", request, List.of(run), List.of(run));
    }

    private static List<String> titles(List<LearningEntry> entries) {
        return entries.stream().map(LearningEntry::title).toList();
    }
}
