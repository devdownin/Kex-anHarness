// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors
package com.kex.agent.harness;

import java.time.Instant;
import java.util.Optional;

public interface ToolResultRepository {
    boolean save(ToolResult result, int maxCount, int maxCharacters, Instant now);
    Optional<ToolResult> find(String id, String owner, String scope, Instant now);
    void clear(String owner, String scope);
}
