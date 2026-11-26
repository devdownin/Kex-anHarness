// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors
package com.kex.agent.agent;

import java.util.List;

import com.kex.agent.agent.AnswerEvidence.Evidence;
import com.kex.agent.agent.AnswerEvidence.Status;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AnswerEvidenceTest {

    private static AgentEvent.ToolCall call(String result) {
        return new AgentEvent.ToolCall("kex_consumer_lag", 12, false, "2026-11-26T08:00:00Z", result);
    }

    private static Evidence check(String answer, String question, AgentEvent.ToolCall... calls) {
        return AnswerEvidence.check(answer, question, List.of(calls));
    }

    @Test
    void un_chiffre_retrouve_dans_un_resultat_d_outil_est_fonde_quelle_que_soit_son_ecriture() {
        Evidence evidence = check("Le groupe order-consumer a 120 000 messages de retard.", "Quel retard ?",
                call("{\"group\":\"order-consumer\",\"lag\":120000}"));

        assertThat(evidence.status()).isEqualTo(Status.GROUNDED);
        assertThat(evidence.unsupported()).isEmpty();
        // Espace fine insécable et virgule de milliers : le même nombre.
        assertThat(check("Retard : 120 000.", "Quel retard ?", call("{\"lag\":120000}")).status())
                .isEqualTo(Status.GROUNDED);
        assertThat(check("Retard : 120,000.", "Quel retard ?", call("{\"lag\":120000}")).status())
                .isEqualTo(Status.GROUNDED);
    }

    @Test
    void un_chiffre_absent_de_tous_les_resultats_est_signale_tel_qu_ecrit() {
        Evidence evidence = check("Le retard est de 120 000 messages, soit 4 521 par partition.", "Quel retard ?",
                call("{\"lag\":4521}"));

        assertThat(evidence.status()).isEqualTo(Status.UNSUPPORTED);
        assertThat(evidence.unsupported()).containsExactly("120 000");
        assertThat(evidence.message()).contains("120 000").contains("tels quels");
    }

    @Test
    void des_chiffres_sans_aucun_outil_appele_sont_dits_comme_tels() {
        Evidence evidence = check("Le retard est de 120 000 messages.", "Quel retard ?");

        assertThat(evidence.status()).isEqualTo(Status.UNSUPPORTED);
        assertThat(evidence.toolCalls()).isZero();
        assertThat(evidence.message()).contains("aucun outil n'ait été appelé");
    }

    @Test
    void un_outil_en_echec_ne_fonde_rien() {
        AgentEvent.ToolCall failed = new AgentEvent.ToolCall("kex_consumer_lag", 5, true, null, "{\"lag\":120000}");

        assertThat(check("Retard : 120000.", "Quel retard ?", failed).status()).isEqualTo(Status.UNSUPPORTED);
    }

    @Test
    void un_resultat_illisible_empeche_de_conclure_plutot_que_d_accuser() {
        // `result` vaut null quand l'extrait n'a pas pu être borné et masqué (texte libre, trop long).
        Evidence evidence = check("Retard : 120 000.", "Quel retard ?", call(null), call("{\"lag\":7}"));

        assertThat(evidence.status()).isEqualTo(Status.UNVERIFIABLE);
        assertThat(evidence.unsupported()).containsExactly("120 000");
        assertThat(evidence.message()).contains("non vérifiables");
    }

    @Test
    void ni_dates_ni_annees_ni_petits_nombres_ni_chiffres_de_la_question_ne_sont_des_mesures() {
        String answer = "Le 2026-11-26 à 08:15:30, en 2026, 12 partitions, groupe 3, ticket 4521 : tout va bien.";

        assertThat(check(answer, "Que dis-tu du ticket 4521 ?").status()).isEqualTo(Status.NOT_APPLICABLE);
        assertThat(check("Au 26/11/2026 à 8:15, rien à signaler.", "Statut ?").status()).isEqualTo(Status.NOT_APPLICABLE);
    }

    @Test
    void un_nombre_n_est_pas_couvert_par_un_prefixe_plus_court() {
        assertThat(check("Retard : 1200.", "Retard ?", call("{\"partitions\":120}")).status()).isEqualTo(Status.UNSUPPORTED);
    }

    @Test
    void une_reponse_sans_chiffre_n_a_rien_a_prouver() {
        Evidence evidence = check("Je ne vois aucun retard sur ce groupe.", "Retard ?");

        assertThat(evidence.status()).isEqualTo(Status.NOT_APPLICABLE);
        assertThat(evidence.message()).isEmpty();
        assertThat(AnswerEvidence.check(null, null, null).status()).isEqualTo(Status.NOT_APPLICABLE);
    }
}
