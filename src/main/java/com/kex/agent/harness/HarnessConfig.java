// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors
package com.kex.agent.harness;

import java.nio.file.Path;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Configuration(proxyBeanMethods = false)
class HarnessConfig {
    @Bean @Profile("!shared-memory")
    ConversationLock localContextLock() { return new ConversationLock(); }
    @Bean @Profile("shared-memory")
    ConversationLock sharedContextLock(JdbcTemplate jdbc, PlatformTransactionManager manager) {
        return new ConversationLock(jdbc, new TransactionTemplate(manager));
    }
    @Bean @Profile("!shared-memory")
    ToolResultRepository localToolResults(ObjectMapper mapper, HarnessProperties properties) {
        return new FileToolResultRepository(mapper, Path.of(properties.results().storePath()));
    }
    @Bean @Profile("shared-memory")
    ToolResultRepository sharedToolResults(JdbcTemplate jdbc, PlatformTransactionManager manager, ConversationLock locks) {
        return new JdbcToolResultRepository(jdbc, new TransactionTemplate(manager), locks);
    }
}
