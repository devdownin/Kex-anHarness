// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors
package com.kex.agent.harness;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kex.agent.memory.DurableFiles;

/** Mono-instance, mêmes permissions privées et écriture atomique que les mémoires existantes. */
public final class FileToolResultRepository implements ToolResultRepository {
    private final DurableFiles files;

    public FileToolResultRepository(ObjectMapper mapper, Path path) {
        files = new DurableFiles(mapper, path);
    }
    public record State(List<ToolResult> results) { }

    private List<ToolResult> live(Instant now) {
        return new ArrayList<>(files.read(State.class, new State(List.of())).results().stream()
                .filter(r -> now.isBefore(r.expiresAt())).toList());
    }
    @Override public synchronized boolean save(ToolResult result, int maxCount, int maxCharacters, Instant now) {
        List<ToolResult> all = live(now);
        var owned = all.stream().filter(r -> r.owner().equals(result.owner())).toList();
        boolean fits = owned.size() < maxCount
                && owned.stream().mapToLong(r -> r.content().length()).sum() + result.content().length() <= maxCharacters;
        if (fits) all.add(result);
        files.write(new State(all));
        return fits;
    }
    @Override public synchronized Optional<ToolResult> find(String id, String owner, String scope, Instant now) {
        List<ToolResult> all = live(now);
        files.write(new State(all));
        return all.stream().filter(r -> r.id().equals(id) && r.owner().equals(owner) && r.scope().equals(scope)).findFirst();
    }
    @Override public synchronized void clear(String owner, String scope) {
        var all = new ArrayList<>(files.read(State.class, new State(List.of())).results());
        all.removeIf(r -> r.owner().equals(owner) && r.scope().equals(scope));
        files.write(new State(all));
    }
}
