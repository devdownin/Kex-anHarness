// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors
package com.kex.agent.skills;

import java.security.Principal;
import java.util.List;
import java.util.Set;

import com.kex.agent.mcp.McpServerUnavailableException;
import com.kex.agent.mcp.UnknownMcpServerException;
import com.kex.agent.mcp.UnsupportedMcpCapabilityException;
import com.kex.agent.supervision.SupervisionService;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

class McpSkillImportControllerTest {
    private final McpSkillImporter importer = mock(McpSkillImporter.class);
    private final SupervisionService supervision = mock(SupervisionService.class);
    private final McpSkillImportController controller = new McpSkillImportController(importer, supervision);

    @Test
    void importe_au_nom_du_locataire_et_l_audit_garde_qui_l_a_demande() {
        Principal admin = () -> "admin";
        McpSkillImporter.Report report = new McpSkillImporter.Report("kafka-explorer", List.of(
                new McpSkillImporter.Outcome("kafka-incident-triage", "skill://kafka-incident-triage/SKILL.md",
                        "sha256:ab", McpSkillImporter.Status.PROPOSED, "En revue", "s-1"),
                new McpSkillImporter.Outcome("kafkaexplorer-release", "skill://kafkaexplorer-release/SKILL.md",
                        "sha256:cd", McpSkillImporter.Status.SKIPPED, "Ingénierie", null)));
        given(importer.importFrom("admin", "kafka-explorer", Set.of())).willReturn(report);
        given(importer.importFrom("admin", "kafka-explorer", Set.of("kafka-incident-triage"))).willReturn(report);

        assertThat(controller.importFrom("kafka-explorer", null, admin)).isEqualTo(report);
        assertThat(controller.importFrom("kafka-explorer", List.of("kafka-incident-triage"), admin)).isEqualTo(report);
        verify(supervision, times(2)).auditAction("admin", "Compétences importées", "kafka-explorer : 1 proposée(s) sur 2");
    }

    @Test
    void sans_identite_rien_n_est_importe() {
        assertThatThrownBy(() -> controller.importFrom("kafka-explorer", null, null))
                .isInstanceOf(ResponseStatusException.class);
    }

    @Test
    void chaque_echec_garde_son_statut() {
        assertThat(controller.unknown(new UnknownMcpServerException("x")).getStatus()).isEqualTo(404);
        assertThat(controller.unsupported(new UnsupportedMcpCapabilityException("x", "resources")).getStatus())
                .isEqualTo(501);
        assertThat(controller.unavailable(new McpServerUnavailableException("x", new RuntimeException())).getStatus())
                .isEqualTo(503);
        assertThat(controller.conflict(new IllegalStateException("pas de catalogue")).getStatus()).isEqualTo(409);
    }
}
