// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors
package com.kex.agent.supervision;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Verrou d'une instance unique : rien à partager avec une autre réplique, puisque l'exploitant a
 * déclaré qu'il n'y en a pas ({@code kex.agent.supervision.schedule.single-instance}). Une suite
 * Compose à une seule instance de l'agent n'avait sinon aucun moyen de superviser seule sans monter
 * une base PostgreSQL pour un verrou que personne ne lui dispute.
 */
final class LocalCycleLock implements CycleLock {

    private final AtomicBoolean held = new AtomicBoolean();

    @Override
    public boolean acquire() {
        return held.compareAndSet(false, true);
    }

    @Override
    public void release() {
        held.set(false);
    }
}
