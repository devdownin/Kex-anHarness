// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors
package com.kex.agent.memory;

import java.time.Instant;
import java.util.List;

import com.kex.agent.memory.SkillProbe.Outcome;
import com.kex.agent.memory.SkillProbe.Run;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SkillProbeReadingTest {
    private static final Instant NOW = Instant.parse("2026-11-25T10:00:00Z");
    private static final String SKILL = "s-1";

    @Test
    void une_seule_passe_rend_l_ecart_confondu_meme_s_il_est_net() {
        SkillProbe probe = probe(List.of(run(List.of("kex_consumer_lag"), "end_turn", false)),
                List.of(run(List.of("kex_dlq_diagnosis"), "end_turn", true)), true);

        assertThat(probe.toolsAdded()).containsExactly("kex_dlq_diagnosis");
        assertThat(probe.reading().outcome()).isEqualTo(Outcome.CONFOUNDED);
        assertThat(probe.reading().reasons()).anyMatch(reason -> reason.contains("Une seule passe"));
    }

    @Test
    void une_candidate_non_lue_a_une_passe_confond_l_ecart() {
        SkillProbe probe = probe(times(3, run(List.of(), "end_turn", false)),
                List.of(run(List.of("kex_dlq_diagnosis"), "end_turn", true),
                        run(List.of("kex_dlq_diagnosis"), "end_turn", true),
                        run(List.of("kex_dlq_diagnosis"), "end_turn", false)), true);

        assertThat(probe.reading().outcome()).isEqualTo(Outcome.CONFOUNDED);
        assertThat(probe.reading().reasons()).containsExactly(
                "Compétence lue dans 2 passe(s) sur 3 : une passe qui ne l'a pas lue n'a pas pu en subir l'effet.");
    }

    @Test
    void une_invariante_n_a_pas_a_etre_lue_pour_agir() {
        SkillProbe probe = probe(times(2, run(List.of(), "end_turn", false)),
                times(2, run(List.of("kex_process_health"), "end_turn", false)), false);

        assertThat(probe.reading().outcome()).isEqualTo(Outcome.STABLE_DIFFERENCE);
        assertThat(probe.reading().reasons()).containsExactly("Outils ajoutés à chaque passe : kex_process_health.");
    }

    @Test
    void des_echecs_epars_confondent_l_ecart() {
        SkillProbe probe = probe(List.of(run(List.of(), "end_turn", false), Run.failed("AgentTimeoutException")),
                times(2, run(List.of(), "end_turn", true)), true);

        assertThat(probe.reading().outcome()).isEqualTo(Outcome.CONFOUNDED);
        assertThat(probe.reading().reasons()).containsExactly("1 passe(s) sur 2 en échec sans la compétence.");
    }

    @Test
    void memes_outils_et_memes_motifs_ne_montrent_aucun_effet() {
        SkillProbe probe = probe(List.of(run(List.of("a"), "end_turn", false), run(List.of("b"), "end_turn", false)),
                List.of(run(List.of("a"), "end_turn", true), run(List.of("b"), "end_turn", true)), true);

        assertThat(probe.toolsAdded()).isEmpty();
        assertThat(probe.toolsDropped()).isEmpty();
        assertThat(probe.reading().outcome()).isEqualTo(Outcome.NO_OBSERVED_EFFECT);
    }

    @Test
    void un_motif_d_arret_different_a_chaque_passe_est_un_ecart_stable() {
        SkillProbe probe = probe(times(2, run(List.of("a"), "end_turn", false)),
                times(2, run(List.of("a"), "max_tokens", true)), true);

        assertThat(probe.reading().outcome()).isEqualTo(Outcome.STABLE_DIFFERENCE);
        assertThat(probe.reading().reasons())
                .containsExactly("Motif d'arrêt différent à chaque passe : end_turn sans, max_tokens avec.");
    }

    @Test
    void un_motif_non_rapporte_ne_departage_rien() {
        SkillProbe probe = probe(List.of(run(List.of("a"), "end_turn", false), run(List.of("a"), null, false)),
                times(2, run(List.of("a"), "max_tokens", true)), true);

        assertThat(probe.reading().outcome()).isEqualTo(Outcome.NO_OBSERVED_EFFECT);
    }

    @Test
    void la_candidate_qui_fait_echouer_chaque_passe_est_un_ecart_stable() {
        SkillProbe probe = probe(times(2, run(List.of("a"), "end_turn", false)),
                times(2, Run.failed("AgentTimeoutException")), false);

        assertThat(probe.reading().outcome()).isEqualTo(Outcome.STABLE_DIFFERENCE);
        assertThat(probe.reading().reasons()).contains("Chaque passe échoue avec la compétence, aucune sans.");
    }

    private static SkillProbe probe(List<Run> references, List<Run> candidates, boolean readRequired) {
        return SkillProbe.of(NOW, "admin", "lag ?", references, candidates, SKILL, readRequired);
    }

    private static Run run(List<String> tools, String finishReason, boolean read) {
        return new Run(tools, 0, null, null, finishReason, "ok", null, read ? List.of(SKILL) : List.of());
    }

    private static List<Run> times(int count, Run run) {
        return java.util.Collections.nCopies(count, run);
    }
}
