// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors
package com.kex.agent.supervision;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * Départ autonome du cycle, derrière un {@link CycleLock}, jamais un {@code @Scheduled} nu sur {@link
 * SupervisionService#runCycle} : en multi-instance, chaque réplique porterait le sien et les actions
 * partiraient en double. Verrou en base sous {@code shared-memory} ({@link JdbcCycleLock}), verrou
 * local quand l'exploitant déclare une instance unique ({@link LocalCycleLock}).
 */
class SupervisionScheduler {

    private static final Logger log = LoggerFactory.getLogger(SupervisionScheduler.class);

    private final SupervisionService supervision;
    private final CycleLock lock;
    private final Clock clock;
    private final SupervisionProperties.Adaptive adaptive;

    SupervisionScheduler(SupervisionService supervision, JdbcTemplate jdbcTemplate, Clock clock,
                        Duration lockAtMostFor, SupervisionProperties.Adaptive adaptive) {
        this(supervision, new JdbcCycleLock(jdbcTemplate, clock, lockAtMostFor), clock, adaptive);
    }

    SupervisionScheduler(SupervisionService supervision, CycleLock lock, Clock clock,
                        SupervisionProperties.Adaptive adaptive) {
        this.supervision = supervision;
        this.lock = lock;
        this.clock = clock;
        this.adaptive = adaptive;
    }

    @Scheduled(fixedDelayString = "${kex.agent.supervision.schedule.interval:5m}")
    void tick() {
        if (!due()) {
            return;
        }
        if (!lock.acquire()) {
            return;
        }
        try {
            supervision.runCycle("scheduler");
        }
        catch (RuntimeException ex) {
            // En pause, cycle déjà en cours ailleurs dans cette même instance : rien d'anormal,
            // la prochaine tentative referra l'état des lieux.
            log.info("Cycle planifié non exécuté : {}", ex.getMessage());
        }
        finally {
            lock.release();
        }
    }

    /**
     * Le battement est régulier ; c'est ici qu'on décide s'il y a lieu d'analyser. Une cadence
     * unique doit choisir entre réagir vite à un incident et ne pas brûler le budget de jetons
     * journalier quand rien ne bouge — indexer le délai minimum sur ce que le dernier cycle a vu
     * lève ce compromis.
     *
     * <p>Le battement qui s'abstient ne prend pas le verrou : une réplique qui juge le prochain
     * cycle non dû n'a pas à empêcher les autres de conclure autrement, et le verrou se prend de
     * toute façon avant l'analyse.
     *
     * <p>Sans cycle connu — premier démarrage, ou historique vidé par un redémarrage — le cycle est
     * dû : c'est l'état {@code UNKNOWN}, celui qui justifie justement d'aller regarder.
     */
    private boolean due() {
        if (!adaptive.enabled()) {
            return true;
        }
        List<CycleReport> cycles = supervision.cycles();
        if (cycles.isEmpty()) {
            return true;
        }
        CycleReport last = cycles.getFirst();
        Instant reference = last.finishedAt() != null ? last.finishedAt() : last.startedAt();
        // Un cycle en échec n'affirme rien sur l'état du cluster : la cadence resserrée est aussi
        // celle qui redonne le plus vite une lecture fiable.
        boolean degraded = last.failure() != null || last.anomaliesDetected() > 0;
        Duration required = degraded ? adaptive.degraded() : adaptive.healthy();
        Instant nextDue = reference.plus(required);
        if (clock.instant().isBefore(nextDue)) {
            log.debug("Cycle non dû avant {} (cadence {} : {})", nextDue,
                    degraded ? "dégradée" : "au repos", required);
            return false;
        }
        return true;
    }
}
