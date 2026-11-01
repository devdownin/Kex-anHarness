// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors
package com.kex.agent.tools;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ToolResultPreviewTest {

    @Test
    void laisse_intact_le_contenu_jusqu_au_plafond() {
        assertThat(ToolResultPreview.bound("", 128)).isEmpty();
        assertThat(ToolResultPreview.bound("x".repeat(128), 128)).isEqualTo("x".repeat(128));
    }

    @Test
    void garde_une_empreinte_connue_du_resultat_complet() {
        // Vecteur SHA-256 publié pour un million de « a » : indépendant du calcul testé.
        assertThat(ToolResultPreview.bound("a".repeat(1000000), 256))
                .hasSize(256).contains("originalCharacters=1000000",
                        "sha256=cdc76e5c9914fb9281a1c7e284d73e67f1809a48a497200e046d39ccc7112cd0");
    }

    @Test
    void conserve_des_extremites_lisibles_au_plafond_minimum() {
        assertThat(ToolResultPreview.bound("DEBUT" + "x".repeat(500) + "FIN", 128))
                .hasSize(128).startsWith("DEBUT").endsWith("FIN").contains("TRUNCATED");
    }

    @Test
    void ne_coupe_pas_les_paires_unicode_aux_deux_frontieres() {
        String result = "😀".repeat(500);
        for (int limit = 128; limit <= 512; limit++) {
            String preview = ToolResultPreview.bound(result, limit);
            assertThat(preview.length()).isLessThanOrEqualTo(limit);
            assertThat(preview).startsWith("😀").endsWith("😀");
            for (int i = 0; i < preview.length(); i++) {
                char current = preview.charAt(i);
                if (Character.isHighSurrogate(current)) {
                    assertThat(i + 1).isLessThan(preview.length());
                    assertThat(Character.isLowSurrogate(preview.charAt(++i))).isTrue();
                }
                else assertThat(Character.isLowSurrogate(current)).isFalse();
            }
        }
    }

    @Test
    void refuse_un_plafond_incompatible_avec_le_marqueur() {
        assertThatThrownBy(() -> ToolResultPreview.bound("court", 127))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
