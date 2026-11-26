// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors
package com.kex.agent.supervision;

/**
 * Ce que la relecture de la chaîne d'audit a établi.
 *
 * @param durable   {@code false} : l'audit vit en mémoire (sans le profil {@code shared-memory}),
 *                  il disparaît au redémarrage et personne ne peut le réécrire en dehors de l'agent
 *                  — il n'y a rien à vérifier, et {@code verified} reste vide plutôt que de dire
 *                  « intègre »
 * @param verified  {@code true} si chaque maillon relu est intact, {@code false} au premier qui ne
 *                  l'est pas, {@code null} quand rien n'a été vérifié
 * @param entries   maillons relus et vérifiés jusqu'au premier défaut (ou jusqu'au bout)
 * @param legacy    entrées écrites avant la chaîne : lisibles, mais sans empreinte à vérifier
 * @param brokenAt  rang du premier maillon en défaut, {@code null} si aucun
 * @param reason    ce qui ne va pas à ce rang
 * @param headSeq   rang du dernier maillon. Une chaîne ne prouve rien d'une troncature de sa fin :
 *                  ce rang et son empreinte, relevés ailleurs à intervalles, la rendent visible
 * @param headHash  empreinte du dernier maillon
 */
public record AuditIntegrity(boolean durable, Boolean verified, long entries, long legacy, Long brokenAt,
                             String reason, Long headSeq, String headHash) {

    static AuditIntegrity notDurable() {
        return new AuditIntegrity(false, null, 0, 0, null,
                "Audit en mémoire : rien de persistant à vérifier (profil shared-memory absent)", null, null);
    }
}
