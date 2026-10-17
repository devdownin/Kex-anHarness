// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors
package com.kex.agent.agent;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ToolContext;

import static org.assertj.core.api.Assertions.assertThat;

class SkillReaderToolTest {

    private final List<String> reads = new ArrayList<>();
    private final SkillReaderTool tool = new SkillReaderTool(Map.of("s-1", "Procédure"), reads::add);

    @Test
    void ne_rend_que_ce_que_l_index_de_l_echange_liste() {
        assertThat(tool.call("{\"id\":\" s-1 \"}")).isEqualTo("Procédure");
        assertThat(tool.call("{\"id\":\"s-2\"}")).contains("Aucune compétence « s-2 »");
        assertThat(tool.call("pas du json")).contains("Aucune compétence");
        assertThat(tool.call("{\"id\":42}")).contains("Aucune compétence");
        assertThat(tool.call(null)).contains("Aucune compétence");
        assertThat(reads).containsExactly("s-1");
        assertThat(tool.getToolDefinition().name()).isEqualTo("read_skill");
        assertThat(tool.getToolDefinition().inputSchema()).contains("\"required\":[\"id\"]");
    }

    @Test
    void chaque_lecture_apparait_parmi_les_outils_de_l_echange() {
        ToolCallRecorder recorder = new ToolCallRecorder();
        ToolContext context = new ToolContext(Map.of(ToolCallRecorder.CONTEXT_KEY, recorder));

        tool.call("{\"id\":\"s-1\"}", context);

        assertThat(recorder.calls()).extracting(AgentEvent.ToolCall::tool).containsExactly("read_skill");
    }
}
