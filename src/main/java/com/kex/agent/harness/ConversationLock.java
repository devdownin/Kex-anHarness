// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors
package com.kex.agent.harness;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.function.Supplier;
import java.util.stream.IntStream;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/** Verrou local à taille fixe ou verrou de ligne partagé, sans garder une transaction pendant un appel LLM. */
public final class ConversationLock {
    private final Object[] stripes = IntStream.range(0, 64).mapToObj(i -> new Object()).toArray();
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;

    public ConversationLock() { this(null, null); }
    public ConversationLock(JdbcTemplate jdbc, TransactionTemplate transactions) {
        this.jdbc = jdbc; this.transactions = transactions;
    }
    public <T> T with(String key, Supplier<T> action) {
        if (jdbc == null) {
            synchronized (stripes[Math.floorMod(key.hashCode(), stripes.length)]) { return action.get(); }
        }
        String id = hash(key);
        try { jdbc.update("INSERT INTO kex_harness_lock(id) VALUES (?)", id); }
        catch (DuplicateKeyException expected) { /* Une autre réplique a déjà créé le verrou. */ }
        return transactions.execute(status -> {
            jdbc.queryForObject("SELECT id FROM kex_harness_lock WHERE id = ? FOR UPDATE", String.class, id);
            return action.get();
        });
    }
    public static String hash(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        }
        catch (NoSuchAlgorithmException ex) { throw new IllegalStateException(ex); }
    }
}
