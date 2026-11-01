// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors
package com.kex.agent.harness;

import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;

import com.kex.agent.testsupport.FlywayTestSchema;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;

class JdbcHarnessTest {
    @Test
    void elargit_le_schema_spring_ai_existant_sans_perdre_son_historique() {
        var source = new SimpleDriverDataSource();
        source.setDriverClass(org.h2.Driver.class);
        source.setUrl("jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
        var jdbc = new JdbcTemplate(source);
        jdbc.execute("""
                CREATE TABLE SPRING_AI_CHAT_MEMORY (
                  conversation_id VARCHAR(36) NOT NULL, content TEXT NOT NULL, type VARCHAR(10) NOT NULL,
                  timestamp TIMESTAMP NOT NULL, sequence_id BIGINT NOT NULL)""");
        jdbc.update("INSERT INTO SPRING_AI_CHAT_MEMORY VALUES (?, ?, ?, CURRENT_TIMESTAMP, ?)", "legacy", "à conserver", "USER", 0);
        FlywayTestSchema.migrate(source);
        assertThat(jdbc.queryForObject("SELECT content FROM SPRING_AI_CHAT_MEMORY WHERE conversation_id = 'legacy'", String.class))
                .isEqualTo("à conserver");
        jdbc.update("INSERT INTO SPRING_AI_CHAT_MEMORY VALUES (?, ?, ?, CURRENT_TIMESTAMP, ?)", "a".repeat(64), "nouveau", "USER", 0);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM SPRING_AI_CHAT_MEMORY", Integer.class)).isEqualTo(2);
    }

    @Test
    void la_migration_et_les_quotas_partages_resistent_aux_admissions_concurrentes() throws Exception {
        var source = new SimpleDriverDataSource();
        source.setDriverClass(org.h2.Driver.class);
        source.setUrl("jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
        FlywayTestSchema.migrate(source);
        var jdbc = new JdbcTemplate(source);
        var transactions = new TransactionTemplate(new DataSourceTransactionManager(source));
        var first = new JdbcToolResultRepository(jdbc, transactions, new ConversationLock(jdbc, transactions));
        var second = new JdbcToolResultRepository(jdbc, transactions, new ConversationLock(jdbc, transactions));
        var now = Instant.parse("2026-11-01T00:00:00Z");
        var result = new ToolResult("one", "tenant", "scope", "read", "{}", "payload", "hash", now, now.plusSeconds(60));
        var other = new ToolResult("two", "tenant", "scope", "read", "{}", "payload", "hash", now, now.plusSeconds(60));
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            var futures = pool.invokeAll(java.util.List.<Callable<Boolean>>of(
                    () -> first.save(result, 1, 100, now), () -> second.save(other, 1, 100, now)));
            assertThat(java.util.List.of(futures.get(0).get(), futures.get(1).get())).containsExactlyInAnyOrder(true, false);
        }
        String id = first.find("one", "tenant", "scope", now).isPresent() ? "one" : "two";
        assertThat(second.find(id, "tenant", "scope", now)).isPresent();
        assertThat(second.find(id, "other", "scope", now)).isEmpty();
        first.clear("tenant", "scope");
        assertThat(second.find(id, "tenant", "scope", now)).isEmpty();
        assertThat(first.save(result, 1, 100, now)).isTrue();
        assertThat(second.find("one", "tenant", "scope", now.plusSeconds(60))).isEmpty();
    }
}
