// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors
package com.kex.agent.memory;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kex.agent.skills.CharterService;
import com.kex.agent.skills.SkillsService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

class DurableContextTest {

    private static final Instant NOW = Instant.parse("2026-10-01T12:00:00Z");

    @TempDir
    Path directory;

    private FileLearningRepository repository;
    private LongTermMemoryService memory;

    @BeforeEach
    void setUp() {
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        repository = new FileLearningRepository(mapper, directory.resolve("learning.json"), 50);
        Clock clock = Clock.fixed(NOW, ZoneId.of("UTC"));
        memory = new LongTermMemoryService(repository, new SkillsService(repository, clock),
                new CharterService(repository, clock),
                new MemoryProperties(true, 200, 500, Duration.ofDays(30),
                        new MemoryProperties.Skills(5, 3000, Duration.ofDays(90))), clock);
        approved("inv", "Citer la couverture", SkillScope.INVARIANT, "# Règle\nToujours citer la couverture.");
        approved("dlq", "Revue DLQ", SkillScope.DOMAIN, """
                # Revue DLQ

                ## Quand l’utiliser
                Quand une file de lettres
                mortes grossit.

                ## Étapes
                Lire kex_dlq_diagnosis avant tout retraitement.""");
    }

    @Test
    void une_procedure_de_domaine_n_entre_que_par_son_index_quand_l_echange_peut_la_lire() {
        DurableContext context = memory.durable("ops", "la dlq déborde", null, true);

        assertThat(context.text())
                .contains("Compétence approuvée : Citer la couverture", "Toujours citer la couverture")
                .contains("- dlq · Revue DLQ — Quand une file de lettres mortes grossit.", "read_skill")
                .doesNotContain("Lire kex_dlq_diagnosis");
        assertThat(context.skills()).containsOnlyKeys("dlq");
        assertThat(context.skills().get("dlq")).contains("Lire kex_dlq_diagnosis");
    }

    @Test
    void sans_outil_permis_la_procedure_est_recopiee_comme_avant() {
        DurableContext context = memory.durable("ops", "la dlq déborde", null, false);

        assertThat(context.text()).contains("Compétence approuvée : Revue DLQ", "Lire kex_dlq_diagnosis");
        assertThat(context.skills()).isEmpty();
        // Sans demande, une procédure à déclencheur n'a pas de sujet : seule l'invariante reste.
        assertThat(memory.context("ops")).contains("Citer la couverture").doesNotContain("Revue DLQ");
    }

    @Test
    void un_proprietaire_sans_contexte_ne_recoit_rien() {
        assertThat(memory.durable("autre", "x", null, true)).isEqualTo(DurableContext.EMPTY);
    }

    @Test
    void sans_section_quand_l_utiliser_l_index_ne_porte_que_le_titre() {
        assertThat(LongTermMemoryService.whenToUse("# Objectif\n\n## Version\nabc123")).isEmpty();
        assertThat(LongTermMemoryService.whenToUse(null)).isEmpty();
        assertThat(LongTermMemoryService.whenToUse("## Quand l'utiliser\n" + "x".repeat(300))).hasSize(200);
    }

    @Test
    void une_lecture_compte_pour_une_approuvee_et_survit_a_une_transition() {
        assertThat(repository.recordRead("dlq", NOW)).isTrue();
        memory.recordSkillRead("dlq");
        LearningEntry pending = new LearningEntry("p", "ops", "SKILL", "P", "M", "E", null, NOW, "PENDING",
                null, null, null);
        repository.add(pending);
        assertThat(repository.recordRead("p", NOW)).isFalse();

        LearningEntry read = repository.list("ops", "SKILL", Instant.EPOCH).stream()
                .filter(entry -> entry.id().equals("dlq")).findFirst().orElseThrow();
        assertThat(read.reads()).isEqualTo(2);
        assertThat(read.lastReadAt()).isEqualTo(NOW);

        // Le retrait relit l'entrée avant d'écrire : il ne doit pas effacer les lectures arrivées entre-temps.
        LearningEntry stale = read.withUsage(0, null).reviewed("RETIRED", "admin", NOW, "fin");
        assertThat(repository.replace(stale, "APPROVED")).isTrue();
        assertThat(repository.list("ops", "SKILL", Instant.EPOCH).stream()
                .filter(entry -> entry.id().equals("dlq")).findFirst().orElseThrow().reads()).isEqualTo(2);
    }

    private void approved(String id, String title, SkillScope scope, String markdown) {
        repository.add(new LearningEntry(id, "ops", "SKILL", title, markdown, "Preuve", null,
                NOW.minusSeconds(60), "APPROVED", "admin", NOW.minusSeconds(30), "ok", null, scope,
                scope == SkillScope.DOMAIN ? List.of("dlq") : List.of(), List.of()));
    }
}
