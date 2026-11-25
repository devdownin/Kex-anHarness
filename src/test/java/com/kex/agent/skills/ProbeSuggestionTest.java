// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors
package com.kex.agent.skills;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import com.kex.agent.memory.LearningEntry;
import com.kex.agent.memory.LearningEvidence;
import com.kex.agent.memory.SkillScope;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ProbeSuggestionTest {
    private static final Instant NOW = Instant.parse("2026-11-25T10:00:00Z");

    @Test
    void une_procedure_de_tache_rejoue_l_objectif_entier_et_non_le_titre_tronque() {
        String objective = "Vérifier le lag du consumer billing-v2 " + "et sa tendance ".repeat(20);
        LearningEvidence proof = new LearningEvidence("VERIFIED", "task:t-1", NOW, NOW.plusSeconds(3600), List.of(),
                "v1", Map.of(), List.of(), List.of());
        LearningEntry entry = entry(objective.substring(0, 160), "# " + objective + "\n\n## Version\nv1", proof,
                List.of());

        ProbeSuggestion suggestion = ProbeSuggestion.of(entry);

        assertThat(suggestion.source()).isEqualTo(ProbeSuggestion.Source.TASK);
        assertThat(suggestion.request()).isEqualTo(objective.strip());
    }

    @Test
    void une_regle_de_refus_rejoue_une_demande_qui_touche_ses_declencheurs() {
        LearningEntry entry = entry(RefusalSkillProposer.TITLE_PREFIX + "RESTART_CONSUMER", "# Règle", null,
                List.of("billing", "orders"));

        ProbeSuggestion suggestion = ProbeSuggestion.of(entry);

        assertThat(suggestion.source()).isEqualTo(ProbeSuggestion.Source.REFUSALS);
        assertThat(suggestion.request()).isEqualTo("Relève l'état des processus « billing », « orders » et dis si "
                + "une action de capacité `RESTART_CONSUMER` serait à proposer, sans l'exécuter.");
        // La demande suggérée doit indexer la candidate, sans quoi le rejeu ne montrerait rien.
        assertThat(SkillsService.matches(SkillsService.normalize(suggestion.request()), entry.triggers())).isTrue();
    }

    @Test
    void un_seul_processus_se_nomme_au_singulier() {
        assertThat(RefusalSkillProposer.probeRequest("PAUSE", List.of("billing")))
                .startsWith("Relève l'état du processus « billing » ");
    }

    @Test
    void une_proposition_manuelle_ne_suggere_rien_mais_rappelle_ses_declencheurs() {
        ProbeSuggestion suggestion = ProbeSuggestion.of(entry("Lag", "Toujours lire la tendance", null, List.of("lag")));

        assertThat(suggestion.source()).isEqualTo(ProbeSuggestion.Source.NONE);
        assertThat(suggestion.request()).isNull();
        assertThat(suggestion.triggers()).containsExactly("lag");
    }

    @Test
    void une_preuve_sans_tache_ne_vaut_pas_objectif() {
        LearningEvidence proof = new LearningEvidence("VERIFIED", "incident:42", NOW, NOW, List.of(), "v1", Map.of(),
                List.of(), List.of());

        assertThat(ProbeSuggestion.of(entry("Lag", "# Titre", proof, List.of())).source())
                .isEqualTo(ProbeSuggestion.Source.NONE);
    }

    private static LearningEntry entry(String title, String markdown, LearningEvidence proof, List<String> triggers) {
        return new LearningEntry("s-1", "ops", "SKILL", title, markdown, "Preuve", null, NOW, "PENDING", null, null,
                null, proof, SkillScope.DOMAIN, triggers, List.of());
    }
}
