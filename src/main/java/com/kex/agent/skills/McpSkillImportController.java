// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors
package com.kex.agent.skills;

import java.security.Principal;
import java.util.List;
import java.util.Set;

import com.kex.agent.config.ActorIdentity;
import com.kex.agent.mcp.McpServerUnavailableException;
import com.kex.agent.mcp.UnknownMcpServerException;
import com.kex.agent.mcp.UnsupportedMcpCapabilityException;
import com.kex.agent.supervision.SupervisionService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Importer, c'est proposer : les compétences lues entrent en revue au nom du locataire de celui qui
 * importe, et l'audit garde qui l'a demandé. Réservé aux administrateurs, comme trancher : un import
 * remplit la file de revue de textes venus d'un serveur tiers.
 */
@RestController
@RequestMapping("/api/agent/skills/import")
@ConditionalOnProperty(prefix = "kex.agent.memory", name = "enabled", havingValue = "true", matchIfMissing = true)
class McpSkillImportController {
    private final McpSkillImporter importer;
    private final SupervisionService supervision;

    McpSkillImportController(McpSkillImporter importer, SupervisionService supervision) {
        this.importer = importer;
        this.supervision = supervision;
    }

    @PostMapping("/{connection}")
    McpSkillImporter.Report importFrom(@PathVariable String connection,
                                       @RequestParam(name = "name", required = false) List<String> names,
                                       Principal principal) {
        if (principal == null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        McpSkillImporter.Report report = importer.importFrom(ActorIdentity.tenantOf(principal), connection,
                names == null ? Set.of() : Set.copyOf(names));
        supervision.auditAction(principal.getName(), "Compétences importées",
                connection + " : " + report.proposed() + " proposée(s) sur " + report.outcomes().size());
        return report;
    }

    @ExceptionHandler(UnknownMcpServerException.class)
    ProblemDetail unknown(UnknownMcpServerException ex) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, ex.getMessage());
    }

    @ExceptionHandler(UnsupportedMcpCapabilityException.class)
    ProblemDetail unsupported(UnsupportedMcpCapabilityException ex) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_IMPLEMENTED, ex.getMessage());
    }

    @ExceptionHandler(McpServerUnavailableException.class)
    ProblemDetail unavailable(McpServerUnavailableException ex) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE, ex.getMessage());
    }

    @ExceptionHandler(IllegalStateException.class)
    ProblemDetail conflict(IllegalStateException ex) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, ex.getMessage());
    }
}
