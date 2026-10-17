// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors
package com.kex.agent.memory;

/**
 * Ce qu'une compétence corrige. Une règle de méthode (citer la couverture, relire le résultat
 * d'outil) vaut pour toute conversation ; une procédure de domaine (lag, DLQ, rétention) ne vaut
 * que là où on en parle. Les confondre sous un seul plafond laissait une procédure de domaine
 * récente évincer une règle transversale plus ancienne.
 */
public enum SkillScope {
    /** Injectée dans toute conversation du propriétaire, sous son propre plafond. */
    INVARIANT,
    /** Injectée seulement quand la demande touche l'un de ses déclencheurs, ou toujours sans déclencheur. */
    DOMAIN
}
