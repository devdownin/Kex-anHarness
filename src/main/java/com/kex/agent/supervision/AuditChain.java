// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors
package com.kex.agent.supervision;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.stream.Stream;

/**
 * L'empreinte d'un maillon de l'audit : SHA-256 de l'empreinte précédente et de chaque champ de
 * l'entrée. Modifier un champ, retirer ou intercaler une ligne change une empreinte que la suivante
 * avait figée.
 *
 * <p>Chaque champ est encodé avec sa longueur, et {@code null} distingué de la chaîne vide : sans
 * cela, déplacer un caractère d'un champ au suivant ({@code "ab" + "c"} contre {@code "a" + "bc"})
 * donnerait la même empreinte. L'horodatage est ramené à la milliseconde, la précision que toutes les
 * bases gardent : relu d'une colonne qui en tronque davantage, il donnerait une autre empreinte que
 * celle calculée à l'écriture.
 */
final class AuditChain {

    /** Empreinte qui précède le premier maillon. */
    static final String GENESIS = "0".repeat(64);

    private AuditChain() {
    }

    static Instant normalized(Instant at) {
        return at == null ? null : at.truncatedTo(ChronoUnit.MILLIS);
    }

    static String hash(long seq, String previousHash, AuditEntry entry) {
        MessageDigest digest = sha256();
        Stream.of(Long.toString(seq), previousHash, entry.id(),
                        entry.at() == null ? null : normalized(entry.at()).toString(), entry.actor(),
                        entry.action(), entry.processId(), entry.decisionId(), entry.reason(),
                        entry.policyVersion(), entry.result(), entry.correlationId(), entry.traceId())
                .forEach(field -> {
                    if (field == null) {
                        digest.update("-1:".getBytes(StandardCharsets.UTF_8));
                    }
                    else {
                        byte[] bytes = field.getBytes(StandardCharsets.UTF_8);
                        digest.update((bytes.length + ":").getBytes(StandardCharsets.UTF_8));
                        digest.update(bytes);
                    }
                });
        return HexFormat.of().formatHex(digest.digest());
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        }
        catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
    }
}
