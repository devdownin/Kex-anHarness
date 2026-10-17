// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors
package com.kex.agent.memory;

import java.time.Instant;
import java.util.List;

import com.kex.agent.testsupport.FlywayTestSchema;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;

class JdbcLearningRepositoryTest {

    private JdbcLearningRepository repository;

    @BeforeEach
    void setUp() {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:test-learning-" + System.nanoTime() + ";DB_CLOSE_DELAY=-1");
        dataSource.setUser("sa");
        dataSource.setPassword("");
        FlywayTestSchema.migrate(dataSource);
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        repository = new JdbcLearningRepository(jdbc, 10);
    }

    @Test
    void performs_learning_crud_review_and_delete() {
        LearningEntry entry = new LearningEntry("l-1", "user1", "SKILL", "Check Lag", "Markdown steps",
                "Evidence", "conv-1", Instant.parse("2026-09-20T10:00:00Z"), "PENDING", null, null, null);

        repository.add(entry);

        List<LearningEntry> list = repository.list("user1", "SKILL", Instant.EPOCH);
        assertThat(list).containsExactly(entry);

        boolean reviewed = repository.review("user1", "l-1", "APPROVED", "admin", Instant.parse("2026-09-20T11:00:00Z"), "Good skill");
        assertThat(reviewed).isTrue();

        List<LearningEntry> updatedList = repository.list("user1", "SKILL", Instant.EPOCH);
        assertThat(updatedList.get(0).status()).isEqualTo("APPROVED");

        boolean deleted = repository.delete("user1", "l-1");
        assertThat(deleted).isTrue();
        assertThat(repository.list("user1", "SKILL", Instant.EPOCH)).isEmpty();
    }

    @Test
    void portee_declencheurs_et_rejeux_survivent_a_la_base() {
        SkillProbe.Run reference = new SkillProbe.Run(List.of("kex_consumer_lag"), 0, 120, null, "end_turn", "ok", null,
                List.of());
        SkillProbe.Run candidate = SkillProbe.Run.failed("AgentTimeoutException");
        SkillProbe probe = SkillProbe.of(Instant.parse("2026-10-01T12:00:00Z"), "admin", "lag ?",
                List.of(reference), List.of(candidate));
        LearningEntry entry = new LearningEntry("l-2", "user1", "SKILL", "DLQ", "Markdown", "Evidence", null,
                Instant.parse("2026-10-01T10:00:00Z"), "PENDING", null, null, null, null,
                SkillScope.DOMAIN, List.of("dlq", "lettre morte"), List.of());
        repository.add(entry);

        assertThat(repository.replace(entry.withProbe(probe), "PENDING")).isTrue();

        LearningEntry stored = repository.list("user1", "SKILL", Instant.EPOCH).getFirst();
        assertThat(stored.scope()).isEqualTo(SkillScope.DOMAIN);
        assertThat(stored.triggers()).containsExactly("dlq", "lettre morte");
        assertThat(stored.probes()).containsExactly(probe);
        assertThat(stored.probes().getFirst().toolsDropped()).containsExactly("kex_consumer_lag");
        assertThat(repository.pending("SKILL").getFirst().probes()).hasSize(1);
    }

    /** Une ligne écrite avant V8 n'a ni portée ni déclencheur : elle doit se relire comme avant. */
    @Test
    void une_ligne_anterieure_se_relit_sans_portee_ni_rejeu() {
        LearningEntry legacy = new LearningEntry("l-3", "user1", "SKILL", "Ancienne", "Markdown", "Evidence",
                null, Instant.parse("2026-09-20T10:00:00Z"), "APPROVED", "admin", Instant.parse("2026-09-21T10:00:00Z"), "ok");
        repository.add(legacy);

        LearningEntry stored = repository.list("user1", "SKILL", Instant.EPOCH).getFirst();
        assertThat(stored).isEqualTo(legacy);
        assertThat(stored.effectiveScope()).isEqualTo(SkillScope.DOMAIN);
    }

    @Test
    void une_lecture_s_incremente_en_base_pour_une_approuvee_seulement() {
        Instant at = Instant.parse("2026-10-02T09:00:00Z");
        repository.add(new LearningEntry("l-4", "user1", "SKILL", "Lag", "Markdown", "Evidence", null,
                Instant.parse("2026-10-01T10:00:00Z"), "APPROVED", "admin", Instant.parse("2026-10-01T11:00:00Z"), "ok"));
        repository.add(new LearningEntry("l-5", "user1", "SKILL", "Lag 2", "Markdown", "Evidence", null,
                Instant.parse("2026-10-01T10:00:00Z"), "PENDING", null, null, null));

        assertThat(repository.recordRead("l-4", at)).isTrue();
        assertThat(repository.recordRead("l-4", at)).isTrue();
        assertThat(repository.recordRead("l-5", at)).isFalse();

        LearningEntry read = repository.list("user1", "SKILL", Instant.EPOCH).stream()
                .filter(entry -> entry.id().equals("l-4")).findFirst().orElseThrow();
        assertThat(read.reads()).isEqualTo(2);
        assertThat(read.lastReadAt()).isEqualTo(at);
    }

    /** Un rejeu écrit avant les répétitions n'avait qu'une exécution par côté : il doit se relire. */
    @Test
    void un_rejeu_a_une_seule_execution_par_cote_se_relit_en_listes() throws Exception {
        String legacy = """
                {"observedAt":"2026-10-01T12:00:00Z","actor":"admin","request":"lag ?",
                 "reference":{"tools":["kex_consumer_lag"],"failedCalls":0,"answer":"ok"},
                 "candidate":{"tools":[],"failedCalls":0,"error":"AgentTimeoutException"},
                 "toolsAdded":[],"toolsDropped":["kex_consumer_lag"]}""";

        SkillProbe probe = new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules()
                .readValue(legacy, SkillProbe.class);

        assertThat(probe.references()).singleElement()
                .satisfies(run -> assertThat(run.tools()).containsExactly("kex_consumer_lag"));
        assertThat(probe.candidates()).singleElement()
                .satisfies(run -> assertThat(run.error()).isEqualTo("AgentTimeoutException"));
        assertThat(probe.tools()).isEmpty();
    }
}
