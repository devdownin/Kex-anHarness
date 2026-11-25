// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors
package com.kex.agent.supervision;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Le départ autonome sur une instance unique déclarée, sans {@code shared-memory} : le contexte
 * monte le planificateur sans base. Sans la déclaration, la même propriété {@code enabled} ne
 * produisait rien hors du profil partagé.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {"kex.agent.supervision.schedule.enabled=true",
                "kex.agent.supervision.schedule.single-instance=true",
                "kex.agent.supervision.schedule.interval=1h"})
@ActiveProfiles("test")
class SingleInstanceScheduleWiringTest {

    @Autowired
    ApplicationContext context;

    @Test
    void monte_le_planificateur_sans_base_quand_l_instance_unique_est_declaree() {
        assertThat(context.getBeansOfType(SupervisionScheduler.class)).hasSize(1);
    }
}
