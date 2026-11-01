// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors
package com.kex.agent.harness;

import java.time.Clock;
import java.util.Map;
import java.util.UUID;

import org.springframework.ai.chat.model.ToolContext;
import org.springframework.stereotype.Service;

@Service
public final class StoredToolResults {
    public static final String SCOPE = "kex.result-scope";
    private final HarnessProperties.Results properties;
    private final ToolResultRepository repository;
    private final Clock clock;

    public StoredToolResults(HarnessProperties properties, ToolResultRepository repository, Clock clock) {
        this.properties = properties.results(); this.repository = repository; this.clock = clock;
    }
    public String offload(String tool, String arguments, String content, ToolContext context) {
        if (!properties.enabled() || context == null || content.length() > properties.maxResultCharacters()) return null;
        Map<String, Object> values = context.getContext();
        if (values.get(com.kex.agent.agent.TaskToolPolicy.ALLOWED_TOOLS) instanceof java.util.Set<?> allowed
                && !allowed.contains("read_tool_result")) return null;
        if (!(values.get("kex.owner") instanceof String owner) || !(values.get(SCOPE) instanceof String scope)) return null;
        var now = clock.instant();
        var result = new ToolResult(UUID.randomUUID().toString(), owner, scope, tool, arguments, content,
                ConversationLock.hash(content), now, now.plus(properties.retention()));
        return repository.save(result, properties.tenantResults(), properties.tenantCharacters(), now) ? result.id() : null;
    }
    public Page read(String id, int offset, int limit, ToolContext context,
                     java.util.function.Consumer<ToolResult> authorize) {
        if (!properties.enabled() || offset < 0 || limit < 1 || limit > properties.pageCharacters() || context == null)
            throw new IllegalArgumentException("Pagination de résultat invalide");
        Object owner = context.getContext().get("kex.owner"), scope = context.getContext().get(SCOPE);
        ToolResult result = repository.find(id, String.valueOf(owner), String.valueOf(scope), clock.instant())
                .orElseThrow(() -> new IllegalArgumentException("Résultat indisponible ou expiré"));
        authorize.accept(result);
        if (offset > result.content().length()) throw new IllegalArgumentException("Position hors résultat");
        int start = offset;
        if (start > 0 && start < result.content().length() && Character.isLowSurrogate(result.content().charAt(start))) start--;
        int end = (int) Math.min((long) result.content().length(), (long) start + limit);
        if (end > start && end < result.content().length() && Character.isHighSurrogate(result.content().charAt(end - 1))) end--;
        if (end == start && start < result.content().length()) throw new IllegalArgumentException("Page trop petite pour ce caractère Unicode");
        return new Page(result.id(), result.tool(), result.sha256(), result.observedAt().toString(), result.expiresAt().toString(),
                start, end, result.content().length(), end < result.content().length(), result.content().substring(start, end));
    }
    public void clear(String owner, String scope) { if (properties.enabled()) repository.clear(owner, scope); }
    public record Page(String id, String tool, String sha256, String observedAt, String expiresAt,
                       int offset, int nextOffset, int originalCharacters, boolean hasMore, String content) { }
}
