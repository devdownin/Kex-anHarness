// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors
package com.kex.agent.supervision.eval;

import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AgentEvalHarnessTest {

    @Test
    void sans_option_le_harnais_reste_la_reference() {
        assertThat(AgentEvalHarness.BASE.withOptions(null)).isSameAs(AgentEvalHarness.BASE);
        assertThat(AgentEvalHarness.BASE.withOptions(" , ")).isSameAs(AgentEvalHarness.BASE);
        assertThat(AgentEvalHarness.BASE.properties()).isEmpty();
    }

    @Test
    void les_options_allument_leurs_proprietes_et_se_lisent_dans_le_rapport() {
        AgentEvalHarness harness = AgentEvalHarness.BASE.withOptions("Selection, compaction,selection");

        assertThat(harness.label()).isEqualTo("base+compaction,selection");
        assertThat(harness.options()).containsExactly("compaction", "selection");
        assertThat(harness.properties()).containsExactlyInAnyOrderEntriesOf(Map.of(
                "kex.agent.harness.compaction.enabled", "true",
                "kex.agent.tools.selection-enabled", "true"));
    }

    @Test
    void deux_jeux_d_options_ne_partagent_pas_d_empreinte() {
        String compaction = AgentEvalHarness.BASE.withOptions("compaction").fingerprint();
        String results = AgentEvalHarness.BASE.withOptions("results").fingerprint();

        assertThat(compaction).isNotEqualTo(results).isNotEqualTo(AgentEvalHarness.BASE.fingerprint());
        assertThat(AgentEvalHarness.BASE.withOptions("results,compaction").fingerprint())
                .isEqualTo(AgentEvalHarness.BASE.withOptions("compaction,results").fingerprint());
    }

    @Test
    void une_option_mal_orthographiee_arrete_l_execution_avant_tout_appel_payant() {
        assertThatThrownBy(() -> AgentEvalHarness.BASE.withOptions("compactoin"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("compactoin")
                .hasMessageContaining("compaction, discovery, results, selection");
    }
}
