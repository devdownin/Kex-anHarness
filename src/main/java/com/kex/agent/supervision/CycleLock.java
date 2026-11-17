// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors
package com.kex.agent.supervision;

/**
 * Ce qui empêche deux départs autonomes du même cycle. En multi-instance, une ligne en base
 * ({@link JdbcCycleLock}) ; sur une instance unique déclarée comme telle, la mémoire du processus
 * ({@link LocalCycleLock}).
 */
interface CycleLock {

    boolean acquire();

    void release();
}
