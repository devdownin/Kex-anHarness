// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors
package com.kex.agent.supervision;

import java.time.Duration;
import java.util.Map;

import org.springframework.boot.context.properties.bind.ConstructorBinding;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Administrator-defined postcondition; no model may provide or change this contract.
 * JSON pointers address the verifier's structuredContent. Only scalar equality is supported.
 * The timestamp must be an ISO-8601 instant observed after the action started; maxAge also
 * bounds its age. If measuredPointer is set, its value must be the boolean true.
 * The MCP tool must separately be authorized as read-only by the tool invocation policy.
 *
 * <p>{@code argumentsFromAction} lets the verifier look for what the action itself produced: each
 * entry copies a scalar from the action's structuredContent (JSON pointer) into a verifier argument,
 * for instance the event id the mutating tool returned. {@code expectedFromActionPointer} does the
 * same for the expected value. Both read the tool's own answer, never the model's: the contract stays
 * administrator-defined, only the values it points at are discovered at execution time. A pointer that
 * finds no scalar makes the outcome unverified rather than guessing.
 */
public record VerificationBinding(String connection, String tool,
                                  @DefaultValue Map<String, Object> arguments,
                                  String resultPointer, Object expectedValue,
                                  String measuredPointer, String observedAtPointer,
                                  @DefaultValue("5m") Duration maxAge,
                                  @DefaultValue Map<String, String> argumentsFromAction,
                                  String expectedFromActionPointer) {

    @ConstructorBinding
    public VerificationBinding {
        arguments = arguments == null ? Map.of() : Map.copyOf(arguments);
        maxAge = maxAge == null ? Duration.ofMinutes(5) : maxAge;
        argumentsFromAction = argumentsFromAction == null ? Map.of() : Map.copyOf(argumentsFromAction);
    }

    public VerificationBinding(String connection, String tool, Map<String, Object> arguments,
                               String resultPointer, Object expectedValue, String measuredPointer,
                               String observedAtPointer, Duration maxAge) {
        this(connection, tool, arguments, resultPointer, expectedValue, measuredPointer, observedAtPointer,
                maxAge, Map.of(), null);
    }
}
