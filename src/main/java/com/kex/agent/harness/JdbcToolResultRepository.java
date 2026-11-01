// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors
package com.kex.agent.harness;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/** Le verrou de locataire sérialise admission, expiration et quota entre répliques. */
public final class JdbcToolResultRepository implements ToolResultRepository {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final ConversationLock locks;

    public JdbcToolResultRepository(JdbcTemplate jdbc, TransactionTemplate transactions, ConversationLock locks) {
        this.jdbc = jdbc; this.transactions = transactions; this.locks = locks;
    }
    @Override public boolean save(ToolResult r, int maxCount, int maxCharacters, Instant now) {
        return locks.with("results:" + r.owner(), () -> transactions.execute(status -> {
            jdbc.update("DELETE FROM kex_tool_result WHERE expires_at <= ?", Timestamp.from(now));
            Long count = jdbc.queryForObject("SELECT COUNT(*) FROM kex_tool_result WHERE owner = ?", Long.class, r.owner());
            Long characters = jdbc.queryForObject("SELECT COALESCE(SUM(characters), 0) FROM kex_tool_result WHERE owner = ?", Long.class, r.owner());
            if (count >= maxCount || characters + r.content().length() > maxCharacters) return false;
            jdbc.update("""
                    INSERT INTO kex_tool_result(id, owner, scope, tool, arguments, content, sha256, observed_at, expires_at, characters)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""", r.id(), r.owner(), r.scope(), r.tool(),
                    r.arguments(), r.content(), r.sha256(), Timestamp.from(r.observedAt()), Timestamp.from(r.expiresAt()), r.content().length());
            return true;
        }));
    }
    @Override public Optional<ToolResult> find(String id, String owner, String scope, Instant now) {
        jdbc.update("DELETE FROM kex_tool_result WHERE expires_at <= ?", Timestamp.from(now));
        return jdbc.query("""
                SELECT * FROM kex_tool_result WHERE id = ? AND owner = ? AND scope = ? AND expires_at > ?""",
                (rs, n) -> new ToolResult(rs.getString("id"), rs.getString("owner"), rs.getString("scope"),
                        rs.getString("tool"), rs.getString("arguments"), rs.getString("content"), rs.getString("sha256"),
                        rs.getTimestamp("observed_at").toInstant(), rs.getTimestamp("expires_at").toInstant()),
                id, owner, scope, Timestamp.from(now)).stream().findFirst();
    }
    @Override public void clear(String owner, String scope) {
        locks.with("results:" + owner, () -> { jdbc.update("DELETE FROM kex_tool_result WHERE owner = ? AND scope = ?", owner, scope); return null; });
    }
}
