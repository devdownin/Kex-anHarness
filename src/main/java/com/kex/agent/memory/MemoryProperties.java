// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors
package com.kex.agent.memory;

import java.time.Duration;
import java.util.Set;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import org.hibernate.validator.constraints.time.DurationMin;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * Plafonds partagés par deux systèmes distincts, tous deux distincts de la fenêtre de conversation
 * ({@code max-history-messages}) : {@link MemoryService} (faits que le modèle choisit explicitement
 * de retenir — {@code remember_fact}/{@code recall_facts}) et {@link LongTermMemoryService} (résumés
 * et compétences écrits automatiquement après chaque échange réussi, sans choix du modèle). Voir
 * {@code docs/ARCHITECTURE.md} (« Un second système partage le nom… ») pour ce que {@code capacity}
 * et {@code retention} couvrent exactement dans l'un et dans l'autre.
 */
@ConfigurationProperties("kex.agent.memory")
@Validated
public record MemoryProperties(

        @DefaultValue("true") boolean enabled,

        /** Souvenirs actifs conservés au-delà de cette limite, le plus ancien évincé en premier. */
        @DefaultValue("200") @Positive int capacity,

        /** Un souvenir plus long est tronqué : l'outil retient un fait, pas un paragraphe. */
        @DefaultValue("500") @Positive int maxContentLength,

        /** Un souvenir non réécrit depuis cette durée n'est plus relu, sans être supprimé. */
        @DefaultValue("30d") @DurationMin(millis = 1) Duration retention,

        @DefaultValue @Valid Skills skills) {

    /**
     * Ce que la bibliothèque de compétences approuvées pèse réellement dans le prompt. Elle ne fait
     * que grandir — chaque échange réussi en propose une — alors que seules les premières de chaque
     * portée agissent. Sans critère de tri, « les cinq premières » dépendait de l'ordre de
     * stockage : la sixième compétence approuvée n'agissait jamais et rien ne le disait.
     *
     * @param injected           compétences de domaine injectées par demande, celles dont un
     *                           déclencheur correspond d'abord, puis les plus récemment approuvées
     * @param invariantInjected  compétences invariantes injectées dans toute conversation : un
     *                           plafond séparé, pour qu'aucune procédure de domaine ne les évince
     * @param charactersPerSkill troncature par compétence, avant le plafond global du bloc de contexte
     * @param staleAfter         au-delà, une compétence approuvée est signalée comme candidate à la
     *                           revue : signalée, jamais retirée d'elle-même
     */
    public record Skills(@DefaultValue("5") @Positive int injected,
                         @DefaultValue("3") @Positive int invariantInjected,
                         @DefaultValue("3000") @Positive int charactersPerSkill,
                         @DefaultValue("90d") @DurationMin(millis = 1) Duration staleAfter,
                         @DefaultValue @Valid Probe probe) {

        @ConstructorBinding
        public Skills {
            probe = probe == null ? new Probe(Set.of()) : probe;
        }

        public Skills(int injected, int charactersPerSkill, Duration staleAfter) {
            this(injected, 3, charactersPerSkill, staleAfter, new Probe(Set.of()));
        }
    }

    /**
     * Le rejeu contrastif d'une compétence en attente. Il exécute le modèle et ses outils pour de
     * vrai : la liste vient donc de la configuration, jamais de la demande rejouée, et vide elle
     * éteint le rejeu — même règle que {@code kex.agent.automation.read-only-tools}.
     */
    public record Probe(@DefaultValue Set<String> readOnlyTools) {
        public Probe {
            readOnlyTools = readOnlyTools == null ? Set.of() : Set.copyOf(readOnlyTools);
        }
    }
}
