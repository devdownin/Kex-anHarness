// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors
package com.kex.agent.supervision;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Une ligne à jour d'expiration, pas {@code pg_advisory_lock} : un verrou consultatif Postgres
 * s'attache à la connexion qui l'a pris, or {@code JdbcTemplate} en emprunte une par appel à un pool.
 * Une réplique qui tombe pendant un cycle libère le verrou d'elle-même à l'expiration.
 */
final class JdbcCycleLock implements CycleLock {

    private static final String LOCK_NAME = "supervision-cycle";

    private final JdbcTemplate jdbcTemplate;
    private final Clock clock;
    private final Duration lockAtMostFor;

    /** Une par instance de processus : distingue dans les journaux qui a tenu le verrou. */
    private final String owner = UUID.randomUUID().toString();

    JdbcCycleLock(JdbcTemplate jdbcTemplate, Clock clock, Duration lockAtMostFor) {
        this.jdbcTemplate = jdbcTemplate;
        this.clock = clock;
        this.lockAtMostFor = lockAtMostFor;
        // Table posée par db/migration/V1__baseline.sql. La ligne du verrou, elle, reste seedée
        // ici plutôt que dans la migration : ce n'est pas du schéma mais un état initial d'instance
        // (expiré dès l'origine), et l'idempotence de ce INSERT ne dépend pas de Flyway.
        jdbcTemplate.update("""
                INSERT INTO kex_supervision_lock (name, locked_until, locked_by)
                SELECT ?, ?, '' WHERE NOT EXISTS (SELECT 1 FROM kex_supervision_lock WHERE name = ?)""",
                LOCK_NAME, Timestamp.from(Instant.EPOCH), LOCK_NAME);
    }

    @Override
    public boolean acquire() {
        Instant now = clock.instant();
        int rows = jdbcTemplate.update(
                "UPDATE kex_supervision_lock SET locked_until = ?, locked_by = ? WHERE name = ? AND locked_until < ?",
                Timestamp.from(now.plus(lockAtMostFor)), owner, LOCK_NAME, Timestamp.from(now));
        return rows == 1;
    }

    /**
     * Relâché tout de suite après le cycle plutôt que d'attendre {@code lockAtMostFor} : sans quoi
     * une autre réplique attendrait ce plafond avant de pouvoir retenter. Conditionné à
     * {@code locked_by = owner} : une instance qui a dépassé le plafond et perdu le verrou ne doit
     * pas relâcher celui qu'une autre a repris entre-temps.
     */
    @Override
    public void release() {
        jdbcTemplate.update("UPDATE kex_supervision_lock SET locked_until = ? WHERE name = ? AND locked_by = ?",
                Timestamp.from(clock.instant()), LOCK_NAME, owner);
    }
}
