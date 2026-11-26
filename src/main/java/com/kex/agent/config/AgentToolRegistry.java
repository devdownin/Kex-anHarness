// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors
package com.kex.agent.config;

import java.util.ArrayList;
import java.util.List;

import com.kex.agent.mcp.ConnectionTrackingToolNames;
import com.kex.agent.mcp.McpToolCatalog;
import com.kex.agent.memory.MemoryTools;
import com.kex.agent.tools.ToolInvocationPolicy;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/** Catalogue réévalué par requête, sans outils par défaut qui contourneraient la sélection. */
@Component
public class AgentToolRegistry {

    private final ObjectProvider<ToolCallbackProvider> providers;
    private final ObjectProvider<MemoryTools> memory;
    private final CircuitBreakerRegistry circuitBreakers;
    private final ToolInvocationPolicy policy;
    private final ObjectProvider<ConnectionTrackingToolNames> toolNames;

    public AgentToolRegistry(ObjectProvider<ToolCallbackProvider> providers, ObjectProvider<MemoryTools> memory,
                             CircuitBreakerRegistry circuitBreakers, ToolInvocationPolicy policy) {
        this(providers, memory, circuitBreakers, policy, null);
    }

    @Autowired
    public AgentToolRegistry(ObjectProvider<ToolCallbackProvider> providers, ObjectProvider<MemoryTools> memory,
                             CircuitBreakerRegistry circuitBreakers, ToolInvocationPolicy policy,
                             ObjectProvider<ConnectionTrackingToolNames> toolNames) {
        this.providers = providers;
        this.memory = memory;
        this.circuitBreakers = circuitBreakers;
        this.policy = policy;
        this.toolNames = toolNames;
    }

    /**
     * Le disjoncteur de la connexion dont l'outil vient — le même que celui de l'appel direct du
     * catalogue —, ou à défaut le disjoncteur commun : un outil dont la connexion est inconnue ne
     * doit pas rester sans protection.
     */
    private CircuitBreaker breakerFor(String toolName) {
        ConnectionTrackingToolNames names = toolNames == null ? null : toolNames.getIfAvailable();
        return names == null ? circuitBreakers.circuitBreaker("mcp-tool")
                : names.connectionOf(toolName)
                        .map(connection -> circuitBreakers.circuitBreaker(McpToolCatalog.circuitBreakerName(connection)))
                        .orElseGet(() -> circuitBreakers.circuitBreaker("mcp-tool"));
    }

    public ToolCallback[] callbacks() {
        List<ToolCallback> result = new ArrayList<>();
        providers.orderedStream().forEach(provider -> result.addAll(List.of(
                new RecordingToolCallbackProvider(provider, this::breakerFor, policy, true).getToolCallbacks())));
        // Les outils mémoire disposent déjà de leur collecteur interne, d'où recordCalls=false.
        memory.stream().forEach(tools -> result.addAll(List.of(new RecordingToolCallbackProvider(
                ToolCallbackProvider.from(ToolCallbacks.from(tools)), circuitBreakers.circuitBreaker("local-tool"), policy, false).getToolCallbacks())));
        return result.stream().filter(tool -> !policy.isDenied(tool.getToolDefinition().name()))
                .toArray(ToolCallback[]::new);
    }
}
