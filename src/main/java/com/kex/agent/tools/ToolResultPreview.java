// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors
package com.kex.agent.tools;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** Conserve aussi la fin : les bilans et avertissements des outils s'y trouvent souvent. */
final class ToolResultPreview {

    private ToolResultPreview() {
    }

    static String bound(String value, int maxCharacters) {
        if (maxCharacters < 128) {
            throw new IllegalArgumentException("Le plafond du résultat doit être d'au moins 128 caractères");
        }
        if (value.length() <= maxCharacters) return value;
        String marker = "\n[TRUNCATED originalCharacters=" + value.length() + " sha256=" + digest(value)
                + "]\n";
        int available = maxCharacters - marker.length();
        int headEnd = (available + 1) / 2;
        int tailStart = value.length() - available / 2;
        // Déplacer les deux frontières vers l'extérieur de la partie conservée : ni paire
        // UTF-16 coupée, ni dépassement du plafond pour sauver un caractère supplémentaire.
        if (headEnd > 0 && Character.isHighSurrogate(value.charAt(headEnd - 1))) headEnd--;
        if (tailStart < value.length() && Character.isLowSurrogate(value.charAt(tailStart))) tailStart++;
        return value.substring(0, headEnd) + marker + value.substring(tailStart);
    }

    private static String digest(String content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(content.getBytes(StandardCharsets.UTF_8)));
        }
        catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 indisponible", ex);
        }
    }
}
