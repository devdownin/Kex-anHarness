// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors
package com.kex.agent.skills;

import java.text.Normalizer;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import com.kex.agent.memory.LearningEntry;
import com.kex.agent.memory.LearningEvidence;
import com.kex.agent.memory.LearningRepository;
import com.kex.agent.memory.MemoryIdentity;
import com.kex.agent.memory.MemoryProperties;
import com.kex.agent.memory.SkillProbe;
import com.kex.agent.memory.SkillScope;

/** No tool can approve skills. Proposals and human decisions are separate entry points. */
public final class SkillsService {
    private final LearningRepository repository;
    private final Clock clock;

    public SkillsService(LearningRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    public LearningEntry propose(String owner, String title, String markdown, String evidence, String conversationId) {
        return propose(owner, title, markdown, evidence, conversationId, null);
    }

    public LearningEntry propose(String owner, String title, String markdown, String evidence,
            String conversationId, LearningEvidence verification) {
        return propose(owner, title, markdown, evidence, conversationId, verification, SkillScope.DOMAIN, List.of());
    }

    public LearningEntry propose(String owner, String title, String markdown, String evidence,
            String conversationId, LearningEvidence verification, SkillScope scope, List<String> triggers) {
        MemoryIdentity.require(owner);
        requireText(title, 200, "titre");
        requireText(markdown, 12000, "procédure Markdown");
        requireText(evidence, 4000, "preuve de réussite / provenance");
        SkillScope effective = scope == null ? SkillScope.DOMAIN : scope;
        List<String> normalized = triggers(triggers);
        // Une invariante s'injecte partout : un déclencheur n'y restreindrait rien et laisserait
        // croire au réviseur qu'elle n'agit que sur certaines demandes.
        if (effective == SkillScope.INVARIANT && !normalized.isEmpty()) {
            throw new IllegalArgumentException("Une compétence invariante ne porte pas de déclencheur");
        }
        LearningEntry entry = new LearningEntry(UUID.randomUUID().toString(), owner, "SKILL", title.strip(),
                markdown.strip(), evidence.strip(), conversationId, clock.instant(), "PENDING", null, null, null,
                verification, effective, normalized, List.of());
        repository.add(entry);
        return entry;
    }

    public List<LearningEntry> list(String owner) {
        return repository.list(MemoryIdentity.require(owner), "SKILL", Instant.EPOCH);
    }

    public List<LearningEntry> approved(String owner) {
        return list(owner).stream().filter(entry -> entry.status().equals("APPROVED")
                && entry.reviewedBy() != null && entry.reviewedAt() != null
                && (entry.verification() == null || entry.verification().usable(clock.instant()))).toList();
    }

    /**
     * Les compétences approuvées dans l'ordre où elles méritent d'agir : la plus récemment approuvée
     * d'abord. Sans critère explicite, « les premières » suivait l'ordre de stockage du dépôt — une
     * compétence approuvée hier pouvait ne jamais agir au profit d'une autre approuvée l'an dernier,
     * et rien à l'écran ne le disait. Faute de télémétrie d'usage, la fraîcheur du verdict humain est
     * le seul critère qui ne soit pas inventé.
     */
    public List<LearningEntry> ranked(String owner) {
        return approved(owner).stream()
                .sorted(Comparator.comparing(LearningEntry::reviewedAt).reversed()
                        .thenComparing(LearningEntry::title))
                .toList();
    }

    /**
     * Ce qui entre réellement dans le prompt pour cette demande : les invariantes sous leur propre
     * plafond, puis les compétences de domaine pertinentes — celles dont un déclencheur correspond
     * d'abord, puis celles sans déclencheur, chaque groupe dans l'ordre de {@link #ranked}.
     *
     * @param request   {@code null} hors demande (curation) : seules les compétences de domaine
     *                  sans déclencheur restent alors éligibles
     * @param candidate une compétence en attente traitée comme si elle venait d'être approuvée —
     *                  le rejeu mesure ainsi exactement ce que l'approbation changerait, éviction
     *                  par le plafond comprise ; {@code null} hors rejeu
     */
    public List<LearningEntry> injectable(String owner, String request, LearningEntry candidate,
                                          MemoryProperties.Skills limits) {
        List<LearningEntry> pool = new ArrayList<>();
        if (candidate != null) pool.add(candidate);
        ranked(owner).stream().filter(entry -> candidate == null || !entry.id().equals(candidate.id()))
                .forEach(pool::add);
        String text = request == null ? null : normalize(request);
        Stream<LearningEntry> invariants = pool.stream()
                .filter(entry -> entry.effectiveScope() == SkillScope.INVARIANT)
                .limit(limits.invariantInjected());
        // Tri stable : « déclenchée d'abord » ne défait pas l'ordre de fraîcheur à l'intérieur d'un groupe.
        Stream<LearningEntry> domain = pool.stream()
                .filter(entry -> entry.effectiveScope() == SkillScope.DOMAIN)
                .filter(entry -> entry.triggers().isEmpty() || text != null && matches(text, entry.triggers()))
                .sorted(Comparator.comparing(entry -> entry.triggers().isEmpty()))
                .limit(limits.injected());
        return Stream.concat(invariants, domain).toList();
    }

    /** La compétence en attente, sous son propriétaire réel — même résolution que {@link #review}. */
    public LearningEntry pending(String id) {
        String owner = ownerOrFail(id, "Compétence inconnue ou déjà examinée");
        return list(owner).stream().filter(entry -> entry.id().equals(id) && "PENDING".equals(entry.status()))
                .findFirst().orElseThrow(() -> new IllegalStateException("Compétence inconnue ou déjà examinée"));
    }

    /**
     * Joint un rejeu à la compétence. La transition reste conditionnée à {@code PENDING} : une
     * revue tranchée pendant le rejeu l'emporte, et le rejeu, devenu sans objet, n'est pas écrit.
     */
    public LearningEntry attachProbe(String id, SkillProbe probe) {
        String owner = ownerOrFail(id, "Compétence inconnue ou déjà examinée");
        LearningEntry entry = list(owner).stream().filter(candidate -> candidate.id().equals(id)).findFirst()
                .orElseThrow(() -> new IllegalStateException("Compétence inconnue ou déjà examinée"));
        LearningEntry next = entry.withProbe(probe);
        if (!"PENDING".equals(entry.status()) || !repository.replace(next, "PENDING")) {
            throw new IllegalStateException("Compétence examinée pendant le rejeu ; rejeu non conservé");
        }
        return next;
    }

    /**
     * Minuscules, sans accents ni espaces répétés : « Rétention » doit déclencher sur
     * « retention », et l'opérateur ne pense pas à écrire les deux.
     */
    static String normalize(String value) {
        String decomposed = Normalizer.normalize(value, Normalizer.Form.NFD).replaceAll("\\p{M}", "");
        return decomposed.strip().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
    }

    /**
     * Le déclencheur doit commencer un mot : « lag » ne correspond pas à « flag », mais
     * « consumer » correspond à « consumers ». Une sous-chaîne nue injecterait la compétence
     * sur des demandes qui n'en parlent pas. Le déclencheur est renormalisé ici aussi : une entrée
     * importée ou écrite hors de {@link #propose} n'est pas passée par la normalisation.
     */
    static boolean matches(String normalizedRequest, List<String> triggers) {
        return triggers.stream().anyMatch(trigger -> Pattern.compile("(?<![\\p{L}\\p{N}])" + Pattern.quote(normalize(trigger)))
                .matcher(normalizedRequest).find());
    }

    private static List<String> triggers(List<String> triggers) {
        if (triggers == null) return List.of();
        Set<String> normalized = new LinkedHashSet<>();
        for (String trigger : triggers) {
            if (trigger == null || trigger.isBlank()) continue;
            String value = normalize(trigger);
            if (value.length() < 2 || value.length() > 60) {
                throw new IllegalArgumentException("Déclencheur de 2 à 60 caractères : " + trigger.strip());
            }
            normalized.add(value);
        }
        if (normalized.size() > 10) throw new IllegalArgumentException("Dix déclencheurs au plus");
        return List.copyOf(normalized);
    }

    /**
     * Retire une compétence approuvée de la bibliothèque. Jamais automatique : le curateur signale,
     * un humain nommé retire — même exigence que l'approbation, et pour la même raison.
     */
    public void retire(String id, String actor, String reason) {
        MemoryIdentity.require(actor);
        if (reason == null || reason.isBlank() || reason.length() > 2000) {
            throw new IllegalArgumentException("Motif de retrait requis et limité à 2000 caractères");
        }
        String owner = ownerOrFail(id, "Compétence inconnue ou non approuvée");
        if (list(owner).stream().noneMatch(entry -> entry.id().equals(id) && entry.status().equals("APPROVED"))) {
            throw new IllegalStateException("Compétence inconnue ou non approuvée");
        }
        LearningEntry entry = list(owner).stream().filter(e -> e.id().equals(id)).findFirst().orElseThrow();
        if (!repository.replace(entry.reviewed("RETIRED", actor, clock.instant(), reason), "APPROVED")) {
            throw new IllegalStateException("Compétence inconnue ou non approuvée");
        }
    }

    /**
     * Approuve ou rejette, quel que soit le propriétaire. La revue est un geste d'administration :
     * elle exige {@code ADMIN}, là où proposer ne demande que de converser et où une compétence
     * tirée de refus appartient à l'opérateur qui a refusé. Chercher l'entrée sous l'identité de
     * l'appelant rendait donc inapprouvable tout ce qu'un non-administrateur avait proposé — la
     * proposition restait {@code PENDING} à vie, faute d'un principal à la fois propriétaire et
     * administrateur.
     *
     * <p>La compétence reste celle de son propriétaire : c'est son contexte qu'elle enrichit une
     * fois approuvée, et {@code actor} garde trace de qui a tranché.
     */
    public LearningEntry review(String id, boolean approve, String actor, String reason) {
        MemoryIdentity.require(actor);
        if (reason == null) reason = "";
        if (reason.length() > 2000) throw new IllegalArgumentException("Motif limité à 2000 caractères");
        String owner = ownerOrFail(id, "Compétence inconnue ou déjà examinée");
        LearningEntry candidate = list(owner).stream().filter(e -> e.id().equals(id)).findFirst()
                .orElseThrow(() -> new IllegalStateException("Compétence inconnue ou déjà examinée"));
        if (approve && candidate.verification() != null && !candidate.verification().usable(clock.instant())) {
            throw new IllegalStateException("La preuve est expirée ou contredite ; une nouvelle proposition est requise");
        }
        if (!repository.review(owner, id, approve ? "APPROVED" : "REJECTED", actor, clock.instant(), reason)) {
            throw new IllegalStateException("Compétence inconnue ou déjà examinée");
        }
        return list(owner).stream().filter(entry -> entry.id().equals(id)).findFirst().orElseThrow();
    }

    /**
     * Tout ce qui attend une décision, tous propriétaires confondus, la plus ancienne d'abord —
     * un ordre de file d'attente, pas celui de {@link #ranked}, qui met en avant l'approuvé le
     * plus récent. Le complément de {@link #review} : approuver exige déjà {@code ADMIN} et porte
     * sur n'importe quel propriétaire, mais rien avant ceci ne permettait de *trouver* ce qu'on
     * a le droit de trancher sans en connaître l'identifiant à l'avance — il fallait le lire dans
     * l'audit, entrée par entrée.
     */
    public List<LearningEntry> pendingReviews() {
        return repository.pending("SKILL");
    }

    private String ownerOrFail(String id, String message) {
        return repository.ownerOf(id).orElseThrow(() -> new IllegalStateException(message));
    }

    private static void requireText(String value, int limit, String field) {
        if (value == null || value.isBlank() || value.length() > limit) {
            throw new IllegalArgumentException(field + " requis et limité à " + limit + " caractères");
        }
    }
}
