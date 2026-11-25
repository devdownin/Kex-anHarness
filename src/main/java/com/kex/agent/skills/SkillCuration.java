// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors
package com.kex.agent.skills;

import java.time.Instant;
import java.util.List;

import com.kex.agent.memory.SkillScope;

/**
 * État de la bibliothèque de compétences approuvées : ce qui agit réellement, et ce qui dort. Un
 * rapport, jamais une action — le retrait passe par un humain nommé, comme l'approbation.
 *
 * @param approved    compétences approuvées au total
 * @param injected    celles qui entrent dans le contexte quelle que soit la demande : les invariantes
 *                    sous leur plafond, puis les compétences de domaine sans déclencheur
 * @param conditional compétences de domaine à déclencheurs : injectées seulement quand la demande
 *                    en touche un, et alors avant celles sans déclencheur
 * @param dormant     approuvées mais au-delà de leur plafond d'injection : elles n'agissent pas
 * @param unread      compétences de domaine jamais lues par {@code read_skill} depuis leur approbation :
 *                    indexées sans que le modèle juge utile de les ouvrir. Les invariantes n'y figurent
 *                    pas — injectées en entier, elles agissent sans lecture à compter
 * @param stale       approuvées depuis plus longtemps que la fenêtre de fraîcheur configurée
 * @param duplicates  groupes de compétences au même titre normalisé, candidates à une consolidation
 * @param characters  caractères que les compétences injectées pèsent dans le prompt
 * @param alwaysOn    ce que tout échange porte avant la demande elle-même, rapporté au budget
 */
public record SkillCuration(int approved, List<Digest> injected, List<Digest> conditional, List<Digest> dormant,
                            List<Digest> unread, List<Digest> stale, List<Duplicate> duplicates, int characters,
                            AlwaysOn alwaysOn) {

    public SkillCuration {
        injected = List.copyOf(injected);
        conditional = List.copyOf(conditional);
        dormant = List.copyOf(dormant);
        unread = List.copyOf(unread);
        stale = List.copyOf(stale);
        duplicates = List.copyOf(duplicates);
    }

    /** @param lastReadAt {@code null} tant qu'aucune lecture n'a eu lieu — pas une date inventée */
    public record Digest(String id, String title, Instant reviewedAt, String reviewedBy, int characters,
                         SkillScope scope, List<String> triggers, int reads, Instant lastReadAt) {
        public Digest {
            triggers = List.copyOf(triggers);
        }
    }

    /**
     * Le texte présent dans tout échange avec outils : le prompt système et le contexte durable du
     * propriétaire tels qu'une demande sans déclencheur les reçoit — charte, résumés vérifiés,
     * invariantes en entier, index des compétences de domaine. Le compte est en caractères : les
     * jetons dépendent du fournisseur, et un seuil en jetons ne se lirait pas sans lui.
     *
     * <p>Une demande qui touche un déclencheur ajoute une ligne d'index par compétence ; le texte
     * d'une procédure n'arrive qu'à sa lecture. Ce compte est donc un plancher par échange.
     *
     * @param overBudget dépassement signalé, jamais corrigé de lui-même
     */
    public record AlwaysOn(int systemPrompt, int durable, int total, int budget, boolean overBudget) {
        static AlwaysOn of(int systemPrompt, int durable, int budget) {
            int total = systemPrompt + durable;
            return new AlwaysOn(systemPrompt, durable, total, budget, total > budget);
        }
    }

    public record Duplicate(String title, List<Digest> entries) {
        public Duplicate {
            entries = List.copyOf(entries);
        }
    }
}
