// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors
package com.kex.agent.harness;

import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kex.agent.tools.ToolControlProperties;
import com.kex.agent.tools.ToolInvocationPolicy;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verifyNoInteractions;

class ToolResultBoundaryTest {
    @TempDir Path directory;

    @Test
    void refuse_un_secret_du_milieu_avant_stockage_et_conserve_une_reference_paginee_pour_le_reste() {
        var mapper = new ObjectMapper().findAndRegisterModules();
        var props = HarnessFixtures.properties(directory.resolve("results.json").toString());
        var stored = spy(new StoredToolResults(props, new FileToolResultRepository(mapper, Path.of(props.results().storePath())),
                Clock.systemUTC()));
        var policy = new ToolInvocationPolicy(new ToolControlProperties(false, 12, 1024, 32000, Map.of(),
                List.of("SECRET-[0-9]+")), mapper);
        ReflectionTestUtils.setField(policy, "results", HarnessFixtures.provider(StoredToolResults.class, stored));
        var context = new ToolContext(HarnessFixtures.context("tenant", "scope"));
        assertThatThrownBy(() -> policy.boundResult("read", "a".repeat(2000) + "SECRET-42" + "b".repeat(2000), "{}", context))
                .isInstanceOf(SecurityException.class).hasMessageNotContaining("SECRET-42");
        verifyNoInteractions(stored);
        String preview = policy.boundResult("read", "DEBUT" + "x".repeat(2000) + "FIN", "{}", context);
        assertThat(preview).hasSizeLessThanOrEqualTo(1024).startsWith("DEBUT").contains("FIN", "TRUNCATED", "read_tool_result");
        var id = java.util.regex.Pattern.compile("id=([a-f0-9-]{36})").matcher(preview);
        assertThat(id.find()).isTrue();
        assertThat(stored.read(id.group(1), 1000, 10, context, r -> assertThat(r.tool()).isEqualTo("read")).content())
                .isEqualTo("x".repeat(10));
    }
}
