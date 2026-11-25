// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors
package com.kex.agent.supervision;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Actif sous le profil {@code shared-memory}, où {@code JdbcTemplate} existe déjà pour la mémoire
 * de conversation. Schéma posé par {@code db/migration/V1__baseline.sql} — voir cette migration
 * pour pourquoi ce n'est plus un {@code CREATE TABLE IF NOT EXISTS} à la construction.
 *
 * <p>Une ligne par entrée, aucune mise à jour : l'audit ne se corrige pas, il s'accumule. Rien,
 * en base, n'empêchait pourtant de réécrire ou de retirer une ligne, et rien ne l'aurait montré. Chaque
 * entrée porte donc son rang et l'empreinte de la précédente ({@link AuditChain}) ;
 * {@link #integrity()} relit la chaîne et dit où elle rompt.
 */
class JdbcAuditRepository implements AuditRepository {

    private final JdbcTemplate jdbcTemplate;

    JdbcAuditRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * Deux répliques peuvent viser le même rang : l'index unique sur {@code seq} refuse la seconde,
     * qui relit le nouveau dernier maillon et recommence. La chaîne ne peut donc pas bifurquer. Le
     * recul aléatoire entre deux tentatives désynchronise des écrivains qui, sinon, se percuteraient à
     * chaque tour : huit écrivains simultanés épuisaient huit tentatives sans recul.
     */
    private static final int CHAIN_ATTEMPTS = 64;

    @Override
    public void add(AuditEntry entry) {
        AuditEntry stored = new AuditEntry(entry.id(), AuditChain.normalized(entry.at()), entry.actor(),
                entry.action(), entry.processId(), entry.decisionId(), entry.reason(), entry.policyVersion(),
                entry.result(), entry.correlationId(), entry.traceId());
        for (int attempt = 1; ; attempt++) {
            List<Map<String, Object>> last = jdbcTemplate.queryForList("""
                    SELECT seq, entry_hash FROM kex_supervision_audit
                    WHERE seq IS NOT NULL ORDER BY seq DESC LIMIT 1""");
            long seq = last.isEmpty() ? 1 : ((Number) last.getFirst().get("seq")).longValue() + 1;
            String previous = last.isEmpty() ? AuditChain.GENESIS : (String) last.getFirst().get("entry_hash");
            try {
                jdbcTemplate.update("""
                        INSERT INTO kex_supervision_audit
                        (id, occurred_at, actor, action, process_id, decision_id, reason, policy_version,
                         result, correlation_id, trace_id, seq, previous_hash, entry_hash)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""",
                        stored.id(), stored.at() == null ? null : Timestamp.from(stored.at()), stored.actor(),
                        stored.action(), stored.processId(), stored.decisionId(), stored.reason(),
                        stored.policyVersion(), stored.result(), stored.correlationId(), stored.traceId(),
                        seq, previous, AuditChain.hash(seq, previous, stored));
                return;
            }
            catch (DuplicateKeyException ex) {
                if (attempt >= CHAIN_ATTEMPTS) throw ex;
                backOff(attempt);
            }
        }
    }

    private static void backOff(int attempt) {
        try {
            Thread.sleep(ThreadLocalRandom.current().nextLong(1, 2L + Math.min(attempt, 10) * 3L));
        }
        catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Écriture de l'audit interrompue", ex);
        }
    }

    @Override
    public AuditIntegrity integrity() {
        long legacy = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM kex_supervision_audit WHERE seq IS NULL", Long.class);
        long[] verified = {0};
        Long[] brokenAt = {null};
        String[] reason = {null};
        long[] expected = {1};
        String[] previous = {AuditChain.GENESIS};
        Long[] headSeq = {null};
        String[] headHash = {null};
        jdbcTemplate.query("""
                SELECT id, occurred_at, actor, action, process_id, decision_id, reason, policy_version,
                       result, correlation_id, trace_id, seq, previous_hash, entry_hash
                FROM kex_supervision_audit WHERE seq IS NOT NULL ORDER BY seq""", (ResultSet rs) -> {
            long seq = rs.getLong("seq");
            headSeq[0] = seq;
            headHash[0] = rs.getString("entry_hash");
            if (brokenAt[0] != null) return;
            AuditEntry entry = entry(rs);
            if (seq != expected[0]) {
                brokenAt[0] = expected[0];
                reason[0] = "Maillon manquant : rang " + expected[0] + " attendu, rang " + seq + " trouvé";
            }
            else if (!previous[0].equals(rs.getString("previous_hash"))) {
                brokenAt[0] = seq;
                reason[0] = "Le maillon ne pointe plus vers le précédent : une ligne a été retirée ou remplacée avant lui";
            }
            else if (!AuditChain.hash(seq, previous[0], entry).equals(rs.getString("entry_hash"))) {
                brokenAt[0] = seq;
                reason[0] = "Le contenu de l'entrée " + entry.id() + " ne correspond plus à son empreinte";
            }
            else {
                verified[0]++;
                expected[0] = seq + 1;
                previous[0] = rs.getString("entry_hash");
            }
        });
        boolean any = verified[0] > 0 || brokenAt[0] != null;
        return new AuditIntegrity(true, any ? brokenAt[0] == null : null, verified[0], legacy, brokenAt[0],
                reason[0], headSeq[0], headHash[0]);
    }

    @Override
    public List<AuditEntry> recent(int limit) {
        return jdbcTemplate.query("""
                SELECT id, occurred_at, actor, action, process_id, decision_id, reason,
                       policy_version, result, correlation_id, trace_id
                FROM kex_supervision_audit ORDER BY occurred_at DESC LIMIT ?""",
                (rs, rowNum) -> entry(rs), limit);
    }

    private static AuditEntry entry(ResultSet rs) throws SQLException {
        return new AuditEntry(rs.getString("id"), toInstant(rs.getTimestamp("occurred_at")),
                rs.getString("actor"), rs.getString("action"), rs.getString("process_id"),
                rs.getString("decision_id"), rs.getString("reason"), rs.getString("policy_version"),
                rs.getString("result"), rs.getString("correlation_id"), rs.getString("trace_id"));
    }

    private static Instant toInstant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }
}
