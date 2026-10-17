// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors
package com.kex.agent.memory;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

/** Schéma posé par {@code db/migration/V1__baseline.sql}, index compris. */
public final class JdbcLearningRepository implements LearningRepository {
    private static final com.fasterxml.jackson.databind.ObjectMapper JSON = new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules();
    private static final RowMapper<LearningEntry> ROW = (rs, row) -> {
        Timestamp reviewedAt = rs.getTimestamp("reviewed_at");
        String scope = rs.getString("scope");
        Timestamp lastReadAt = rs.getTimestamp("last_read_at");
        return new LearningEntry(rs.getString("id"), rs.getString("owner"), rs.getString("kind"),
                rs.getString("title"), rs.getString("markdown"), rs.getString("evidence"),
                rs.getString("conversation_id"), rs.getTimestamp("created_at").toInstant(),
                rs.getString("status"), rs.getString("reviewed_by"),
                reviewedAt == null ? null : reviewedAt.toInstant(), rs.getString("review_reason"),
                decode(rs.getString("verification"), LearningEvidence.class),
                scope == null ? null : SkillScope.valueOf(scope),
                List.of(decode(rs.getString("triggers"), String[].class, new String[0])),
                List.of(decode(rs.getString("probes"), SkillProbe[].class, new SkillProbe[0])),
                rs.getInt("reads"), lastReadAt == null ? null : lastReadAt.toInstant());
    };
    private final JdbcTemplate jdbc;
    private final int capacity;

    public JdbcLearningRepository(JdbcTemplate jdbc, int capacity) {
        this.jdbc = jdbc;
        this.capacity = Math.max(1, capacity);
    }

    @Override
    public void add(LearningEntry entry) {
        jdbc.update("""
                INSERT INTO kex_agent_learning (id, owner, kind, title, markdown, evidence, conversation_id,
                  created_at, status, reviewed_by, reviewed_at, review_reason, verification, scope, triggers, probes)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""",
                entry.id(), entry.owner(), entry.kind(), entry.title(), entry.markdown(), entry.evidence(),
                entry.conversationId(), Timestamp.from(entry.createdAt()), entry.status(), entry.reviewedBy(),
                entry.reviewedAt() == null ? null : Timestamp.from(entry.reviewedAt()), entry.reviewReason(), encode(entry.verification()),
                entry.scope() == null ? null : entry.scope().name(), encode(entry.triggers()), encode(entry.probes()));
        jdbc.update("""
                DELETE FROM kex_agent_learning WHERE kind = ? AND id NOT IN (
                  SELECT id FROM kex_agent_learning WHERE kind = ? ORDER BY created_at DESC, id DESC LIMIT ?)
                """, entry.kind(), entry.kind(), capacity);
    }

    @Override
    public List<LearningEntry> list(String owner, String kind, Instant since) {
        return jdbc.query("""
                SELECT * FROM kex_agent_learning WHERE owner = ? AND kind = ? AND created_at >= ?
                ORDER BY created_at DESC, id DESC""", ROW, owner, kind, Timestamp.from(since));
    }

    @Override
    public boolean review(String owner, String id, String status, String actor, Instant at, String reason) {
        return jdbc.update("""
                UPDATE kex_agent_learning SET status = ?, reviewed_by = ?, reviewed_at = ?, review_reason = ?
                WHERE id = ? AND owner = ? AND kind = 'SKILL' AND status = 'PENDING'""",
                status, actor, Timestamp.from(at), reason, id, owner) == 1;
    }

    @Override
    public boolean replace(LearningEntry entry, String expectedStatus) {
        return jdbc.update("""
                UPDATE kex_agent_learning SET status = ?, reviewed_by = ?, reviewed_at = ?, review_reason = ?, verification = ?, probes = ?
                WHERE id = ? AND owner = ? AND status = ?""",
                entry.status(), entry.reviewedBy(), entry.reviewedAt() == null ? null : Timestamp.from(entry.reviewedAt()),
                entry.reviewReason(), encode(entry.verification()), encode(entry.probes()), entry.id(), entry.owner(), expectedStatus) == 1;
    }

    /** Une liste vide s'écrit {@code NULL} : rien à relire ne se distingue alors d'une ligne antérieure. */
    private static String encode(Object value) {
        if (value == null || value instanceof List<?> list && list.isEmpty()) return null;
        try { return JSON.writeValueAsString(value); }
        catch (Exception ex) { throw new IllegalArgumentException("Preuve non sérialisable", ex); }
    }
    private static <T> T decode(String value, Class<T> type) {
        return decode(value, type, null);
    }
    private static <T> T decode(String value, Class<T> type, T absent) {
        try { return value == null ? absent : JSON.readValue(value, type); }
        catch (Exception ex) { throw new IllegalStateException("Preuve persistée illisible", ex); }
    }

    @Override
    public boolean delete(String owner, String id) {
        return jdbc.update("DELETE FROM kex_agent_learning WHERE id = ? AND owner = ?", id, owner) == 1;
    }

    @Override
    public Optional<String> ownerOf(String id) {
        return jdbc.query("SELECT owner FROM kex_agent_learning WHERE id = ?",
                (rs, row) -> rs.getString("owner"), id).stream().findFirst();
    }

    @Override
    public boolean recordRead(String id, Instant at) {
        return jdbc.update("""
                UPDATE kex_agent_learning SET reads = reads + 1, last_read_at = ?
                WHERE id = ? AND kind = 'SKILL' AND status = 'APPROVED'""", Timestamp.from(at), id) == 1;
    }

    @Override
    public List<LearningEntry> pending(String kind) {
        return jdbc.query("""
                SELECT * FROM kex_agent_learning WHERE kind = ? AND status = 'PENDING'
                ORDER BY created_at ASC, id ASC""", ROW, kind);
    }
}
