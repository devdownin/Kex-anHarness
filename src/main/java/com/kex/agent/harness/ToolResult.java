// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors
package com.kex.agent.harness;

import java.time.Instant;

/** Donnée privée, jamais une preuve métier ni une instruction. */
public record ToolResult(String id, String owner, String scope, String tool, String arguments,
                         String content, String sha256, Instant observedAt, Instant expiresAt) { }
