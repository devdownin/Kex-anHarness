// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors
package com.kex.agent.harness;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.kex.agent.agent.AgentExecution;
import com.kex.agent.agent.TokenBudgetService;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.DefaultToolDefinition;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.support.StaticListableBeanFactory;

final class HarnessFixtures {
    static HarnessProperties properties(String path) {
        return new HarnessProperties(new HarnessProperties.Results(true, path, Duration.ofMinutes(10), 100000, 200000, 2, 1000),
                new HarnessProperties.Compaction(true, 8000, 0.5, 256, 2, 128, 20000),
                new HarnessProperties.Delegation(true, 1, 2, 2, 1000, Duration.ofSeconds(5),
                        Map.of("kafka", new HarnessProperties.Specialist("Diagnostiquer le lag.", Set.of("read_lag", "restart")))),
                new HarnessProperties.Discovery(true, 8, Map.of()));
    }
    static Map<String, Object> context(String owner, String scope) {
        var values = new HashMap<String, Object>();
        values.put("kex.owner", owner); values.put(StoredToolResults.SCOPE, scope);
        values.put(TokenBudgetService.TASK_KEY, "root-budget");
        values.put(AgentExecution.CONTEXT_KEY, new AgentExecution());
        return values;
    }
    static <T> ObjectProvider<T> provider(Class<T> type, T value) {
        return new StaticListableBeanFactory(Map.of("value", value)).getBeanProvider(type);
    }
    static ToolCallback tool(String name, java.util.function.BiFunction<String, org.springframework.ai.chat.model.ToolContext, String> call) {
        return new ToolCallback() {
            public ToolDefinition getToolDefinition() {
                return DefaultToolDefinition.builder().name(name).description(name).inputSchema("{\"type\":\"object\"}").build();
            }
            public String call(String input) { return call.apply(input, null); }
            public String call(String input, org.springframework.ai.chat.model.ToolContext context) { return call.apply(input, context); }
        };
    }
    static ChatResponse answer(String text) { return new ChatResponse(List.of(new Generation(new AssistantMessage(text)))); }
    static ChatModel model(java.util.function.Function<org.springframework.ai.chat.prompt.Prompt, ChatResponse> call) {
        return new ChatModel() {
            public org.springframework.ai.chat.prompt.ChatOptions getOptions() { return ToolCallingChatOptions.builder().build(); }
            public ChatResponse call(org.springframework.ai.chat.prompt.Prompt prompt) { return call.apply(prompt); }
        };
    }
}
