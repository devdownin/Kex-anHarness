// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors
package com.kex.agent.harness;

public final class UntrustedText {
    private UntrustedText() { }
    public static String prefix(String value, int characters) {
        int end = Math.min(value.length(), characters);
        if (end > 0 && end < value.length() && Character.isHighSurrogate(value.charAt(end - 1))) end--;
        return value.substring(0, end);
    }
    public static String escape(String value) {
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;").replace("'", "&apos;");
    }
    public static String wrap(String name, String content) {
        return "<tool_result tool=\"" + escape(name) + "\" trust=\"untrusted\">\n" + escape(content) + "\n</tool_result>";
    }
}
