// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors
package com.kex.agent.supervision;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;

import com.kex.agent.testsupport.FlywayTestSchema;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Sur H2 plutôt qu'un contexte Spring complet : ce qui est en jeu est le SQL et le mappage d'une
 * ligne, pas le câblage du profil {@code shared-memory} — {@code SharedMemoryProfileTest} le
 * couvre déjà pour la mémoire de conversation.
 */
class JdbcAuditRepositoryTest {

    private final JdbcTemplate jdbc = new JdbcTemplate(dataSource());
    private final JdbcAuditRepository repository = new JdbcAuditRepository(jdbc);

    private static DataSource dataSource() {
        SimpleDriverDataSource dataSource = new SimpleDriverDataSource();
        dataSource.setDriverClass(org.h2.Driver.class);
        dataSource.setUrl("jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
        FlywayTestSchema.migrate(dataSource);
        return dataSource;
    }

    @Test
    void persiste_et_relit_du_plus_recent_au_plus_ancien() {
        Instant first = Instant.parse("2026-09-15T05:00:00Z");
        repository.add(new AuditEntry("a1", first, "Agent", "action-1", "p1", "d1", "raison",
                "policy-v1", "resultat", "corr-1", "trace-1"));
        repository.add(new AuditEntry("a2", first.plusSeconds(5), "opérateur", "action-2", "p1",
                "d1", null, "policy-v1", "resultat-2", "corr-1", null));

        List<AuditEntry> recent = repository.recent(10);

        assertThat(recent).extracting(AuditEntry::id).containsExactly("a2", "a1");
        assertThat(recent.get(1).traceId()).isEqualTo("trace-1");
        assertThat(recent.get(0).traceId()).isNull();
    }

    @Test
    void borne_le_nombre_d_entrees_rendues() {
        Instant at = Instant.parse("2026-09-15T05:00:00Z");
        for (int i = 0; i < 5; i++) {
            repository.add(new AuditEntry("a" + i, at.plusSeconds(i), "Agent", "action", null, null,
                    null, "policy-v1", null, "corr-" + i, null));
        }

        assertThat(repository.recent(2)).hasSize(2);
    }

    private void write(int count) {
        Instant at = Instant.parse("2026-09-15T05:00:00.123456789Z");
        for (int i = 1; i <= count; i++) {
            repository.add(new AuditEntry("a" + i, at.plusSeconds(i), "opérateur", "Validation : action " + i,
                    "p1", "d" + i, i % 2 == 0 ? null : "raison " + i, "policy-v1", "EXECUTED", "corr-" + i, null));
        }
    }

    @Test
    void une_chaine_intacte_se_verifie_jusqu_a_son_dernier_maillon() {
        write(5);

        AuditIntegrity integrity = repository.integrity();

        assertThat(integrity.durable()).isTrue();
        assertThat(integrity.verified()).isTrue();
        assertThat(integrity.entries()).isEqualTo(5);
        assertThat(integrity.brokenAt()).isNull();
        assertThat(integrity.headSeq()).isEqualTo(5);
        assertThat(integrity.headHash()).hasSize(64);
    }

    @Test
    void une_entree_reecrite_en_base_rompt_la_chaine_a_son_rang() {
        write(5);
        jdbc.update("UPDATE kex_supervision_audit SET result = 'REJECTED' WHERE id = 'a3'");

        AuditIntegrity integrity = repository.integrity();

        assertThat(integrity.verified()).isFalse();
        assertThat(integrity.brokenAt()).isEqualTo(3);
        assertThat(integrity.entries()).isEqualTo(2);
        assertThat(integrity.reason()).contains("a3");
    }

    @Test
    void une_entree_retiree_se_voit_au_maillon_manquant() {
        write(5);
        jdbc.update("DELETE FROM kex_supervision_audit WHERE id = 'a2'");

        AuditIntegrity integrity = repository.integrity();

        assertThat(integrity.verified()).isFalse();
        assertThat(integrity.brokenAt()).isEqualTo(2);
        assertThat(integrity.reason()).contains("manquant");
    }

    @Test
    void une_entree_remplacee_par_une_copie_recalculee_ne_trompe_pas_le_maillon_suivant() {
        write(4);
        // Le faussaire recalcule l'empreinte de la ligne qu'il modifie : elle redevient cohérente avec
        // elle-même, mais la suivante a figé l'ancienne.
        AuditEntry forged = new AuditEntry("a2", Instant.parse("2026-09-15T05:00:02.123Z"), "opérateur",
                "Validation : action 2", "p1", "d2", null, "policy-v1", "REJECTED", "corr-2", null);
        String previous = jdbc.queryForObject("SELECT entry_hash FROM kex_supervision_audit WHERE seq = 1", String.class);
        jdbc.update("UPDATE kex_supervision_audit SET result = 'REJECTED', entry_hash = ? WHERE id = 'a2'",
                AuditChain.hash(2, previous, forged));

        AuditIntegrity integrity = repository.integrity();

        assertThat(integrity.brokenAt()).isEqualTo(3);
        assertThat(integrity.reason()).contains("précédent");
    }

    @Test
    void les_entrees_d_avant_la_chaine_sont_comptees_a_part_sans_etre_dites_integres() {
        jdbc.update("""
                INSERT INTO kex_supervision_audit (id, occurred_at, actor, action, policy_version)
                VALUES ('ancienne', CURRENT_TIMESTAMP, 'Agent', 'Cycle', 'policy-v0')""");

        assertThat(repository.integrity().verified()).isNull();
        assertThat(repository.integrity().legacy()).isEqualTo(1);

        write(2);
        assertThat(repository.integrity().verified()).isTrue();
        assertThat(repository.integrity().legacy()).isEqualTo(1);
    }

    // Rejoué : une course qui ne se perd qu'une fois sur dix passerait un test exécuté une seule fois.
    @org.junit.jupiter.api.RepeatedTest(20)
    void des_ecritures_concurrentes_ne_font_pas_bifurquer_la_chaine() throws Exception {
        int writers = 8;
        var pool = java.util.concurrent.Executors.newFixedThreadPool(writers);
        try {
            var start = new java.util.concurrent.CountDownLatch(1);
            List<java.util.concurrent.Future<?>> futures = new java.util.ArrayList<>();
            for (int w = 0; w < writers; w++) {
                int writer = w;
                futures.add(pool.submit(() -> {
                    start.await();
                    for (int i = 0; i < 5; i++) {
                        repository.add(new AuditEntry("w" + writer + "-" + i, Instant.now(), "Agent", "action",
                                null, null, null, "policy-v1", null, "corr", null));
                    }
                    return null;
                }));
            }
            start.countDown();
            for (var future : futures) future.get();
        }
        finally {
            pool.shutdownNow();
        }

        AuditIntegrity integrity = repository.integrity();
        assertThat(integrity.verified()).isTrue();
        assertThat(integrity.entries()).isEqualTo(40);
        assertThat(integrity.headSeq()).isEqualTo(40);
    }

    @Test
    void l_audit_en_memoire_ne_se_dit_pas_integre() {
        AuditIntegrity integrity = new InMemoryAuditRepository(10).integrity();

        assertThat(integrity.durable()).isFalse();
        assertThat(integrity.verified()).isNull();
    }
}
