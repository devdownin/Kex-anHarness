// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors
package com.kex.agent.agent;

import java.util.List;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AutoroutesOperationalPlaybookTest {
    @Test
    void aucune_procedure_sans_outil_autoroutes_autorise() {
        assertThat(AutoroutesOperationalPlaybook.forTools(List.of())).isEmpty();
        assertThat(AutoroutesOperationalPlaybook.forTools(List.of("kex_cluster_audit", "fake_declarer_anomalies"))).isEmpty();
    }

    @Test
    void preserve_les_noms_qualifies_et_la_garde_de_remplacement() {
        String guidance = AutoroutesOperationalPlaybook.forTools(List.of("mcpautoroute__declarer_anomalies",
                "mcpautoroute__consulter_anomalies", "mcpautoroute__rechercher_gares"));
        assertThat(guidance).contains("mcpautoroute__declarer_anomalies", "version_attendue", "maSource",
                "REMPLACE", "ne pas incrémenter", "Ne jamais recopier", "eventId", "vérification en attente");
    }

    @Test
    void la_lecture_seule_ne_devient_pas_une_autorisation_de_publication() {
        String guidance = AutoroutesOperationalPlaybook.forTools(List.of("rechercher_gares"));
        assertThat(guidance).startsWith("Outils autoroutes disponibles pour cet échange (utiliser leurs noms exacts) : rechercher_gares\n");
        assertThat(guidance).contains("toutes deux autorisées", "Ne pas appeler un outil absent", "lecture seule",
                "rattachement topic/processus-gare absent", "levée totale", "résolution");
    }
}
