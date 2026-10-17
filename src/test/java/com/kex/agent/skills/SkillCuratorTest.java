// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors
package com.kex.agent.skills;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import com.kex.agent.memory.LearningEntry;
import com.kex.agent.memory.LearningRepository;
import com.kex.agent.memory.MemoryProperties;
import com.kex.agent.memory.SkillScope;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SkillCuratorTest {

    private static final Instant NOW = Instant.parse("2026-09-21T10:00:00Z");
    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
    private final FakeRepository repository = new FakeRepository();
    private final SkillsService skills = new SkillsService(repository, clock);

    private SkillCurator curator(int injected, Duration staleAfter) {
        return new SkillCurator(skills,
                new MemoryProperties(true, 200, 500, Duration.ofDays(30),
                        new MemoryProperties.Skills(injected, 3000, staleAfter)),
                clock);
    }

    @Test
    void classe_la_plus_recemment_approuvee_en_premier() {
        approved("ancienne", NOW.minus(Duration.ofDays(10)));
        approved("récente", NOW.minus(Duration.ofDays(1)));

        assertThat(skills.ranked("ops")).extracting(LearningEntry::title)
                .containsExactly("récente", "ancienne");
    }

    /** Le défaut que ce curateur existe pour rendre visible : au-delà du plafond, rien n'agit. */
    @Test
    void signale_les_competences_approuvees_qui_n_agissent_jamais() {
        approved("première", NOW.minus(Duration.ofDays(1)));
        approved("deuxième", NOW.minus(Duration.ofDays(2)));
        approved("troisième", NOW.minus(Duration.ofDays(3)));

        SkillCuration curation = curator(2, Duration.ofDays(90)).curate("ops");

        assertThat(curation.approved()).isEqualTo(3);
        assertThat(curation.injected()).extracting(SkillCuration.Digest::title)
                .containsExactly("première", "deuxième");
        assertThat(curation.dormant()).extracting(SkillCuration.Digest::title).containsExactly("troisième");
    }

    @Test
    void groupe_les_titres_en_double_a_la_casse_et_aux_espaces_pres() {
        approved("Redémarrer un consumer", NOW.minus(Duration.ofDays(1)));
        approved("  redémarrer   un CONSUMER ", NOW.minus(Duration.ofDays(2)));
        approved("Purger un topic", NOW.minus(Duration.ofDays(3)));

        SkillCuration curation = curator(5, Duration.ofDays(90)).curate("ops");

        assertThat(curation.duplicates()).singleElement()
                .satisfies(duplicate -> assertThat(duplicate.entries()).hasSize(2));
    }

    @Test
    void signale_une_competence_approuvee_depuis_trop_longtemps() {
        approved("vieille règle", NOW.minus(Duration.ofDays(200)));
        approved("règle du mois", NOW.minus(Duration.ofDays(5)));

        SkillCuration curation = curator(5, Duration.ofDays(90)).curate("ops");

        assertThat(curation.stale()).extracting(SkillCuration.Digest::title).containsExactly("vieille règle");
    }

    /** Une proposition en attente n'agit pas : elle n'a rien à faire dans le compte des injectées. */
    @Test
    void ignore_ce_qui_n_est_pas_approuve() {
        skills.propose("ops", "en attente", "# procédure", "preuve", null);

        SkillCuration curation = curator(5, Duration.ofDays(90)).curate("ops");

        assertThat(curation.approved()).isZero();
        assertThat(curation.injected()).isEmpty();
    }

    @Test
    void le_retrait_exige_un_motif_et_une_competence_approuvee() {
        LearningEntry entry = approved("règle", NOW.minus(Duration.ofDays(1)));

        assertThatThrownBy(() -> skills.retire(entry.id(), "admin", " "))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> skills.retire("inconnue", "admin", "obsolète"))
                .isInstanceOf(IllegalStateException.class);

        skills.retire(entry.id(), "admin", "obsolète");

        assertThat(skills.approved("ops")).isEmpty();
    }

    /**
     * Une compétence à déclencheurs n'est ni injectée d'office ni en sommeil : la lister parmi les
     * dormantes pousserait à retirer ce qui agit précisément quand on en parle.
     */
    @Test
    void separe_les_invariantes_les_conditionnelles_et_les_dormantes() {
        scoped("invariante", SkillScope.INVARIANT, List.of(), NOW.minus(Duration.ofDays(5)));
        scoped("dlq", SkillScope.DOMAIN, List.of("dlq"), NOW.minus(Duration.ofDays(1)));
        approved("générale récente", NOW.minus(Duration.ofDays(2)));
        approved("générale ancienne", NOW.minus(Duration.ofDays(3)));

        SkillCuration report = curator(1, Duration.ofDays(90)).curate("ops");

        assertThat(report.injected()).extracting(SkillCuration.Digest::title)
                .containsExactly("invariante", "générale récente");
        assertThat(report.conditional()).extracting(SkillCuration.Digest::title).containsExactly("dlq");
        assertThat(report.conditional().getFirst().triggers()).containsExactly("dlq");
        assertThat(report.dormant()).extracting(SkillCuration.Digest::title).containsExactly("générale ancienne");
        assertThat(report.injected().getFirst().scope()).isEqualTo(SkillScope.INVARIANT);
    }

    /** Une invariante agit sans lecture : la ranger parmi les « jamais lues » pousserait à la retirer. */
    @Test
    void signale_les_procedures_de_domaine_jamais_lues_et_compte_les_lectures() {
        scoped("invariante", SkillScope.INVARIANT, List.of(), NOW.minus(Duration.ofDays(3)));
        approved("lue", NOW.minus(Duration.ofDays(2)));
        approved("ignorée", NOW.minus(Duration.ofDays(1)));
        repository.recordRead("id-lue", NOW);

        SkillCuration report = curator(5, Duration.ofDays(90)).curate("ops");

        assertThat(report.unread()).extracting(SkillCuration.Digest::title).containsExactly("ignorée");
        assertThat(report.injected()).filteredOn(digest -> digest.title().equals("lue"))
                .singleElement().satisfies(digest -> {
                    assertThat(digest.reads()).isEqualTo(1);
                    assertThat(digest.lastReadAt()).isEqualTo(NOW);
                });
    }

    private void scoped(String title, SkillScope scope, List<String> triggers, Instant reviewedAt) {
        repository.add(new LearningEntry("id-" + title, "ops", "SKILL", title, "# " + title, "preuve", null,
                reviewedAt, "APPROVED", "admin", reviewedAt, "ok", null, scope, triggers, List.of()));
    }

    private LearningEntry approved(String title, Instant reviewedAt) {
        LearningEntry entry = new LearningEntry("id-" + title.strip(), "ops", "SKILL", title,
                "# " + title, "preuve", null, reviewedAt, "APPROVED", "admin", reviewedAt, "ok");
        repository.add(entry);
        return entry;
    }

    /** Un dépôt qui rend les entrées dans l'ordre d'insertion : c'est là que naissait le défaut. */
    private static final class FakeRepository implements LearningRepository {
        private final List<LearningEntry> entries = new ArrayList<>();

        @Override
        public void add(LearningEntry entry) {
            entries.add(entry);
        }

        @Override
        public List<LearningEntry> list(String owner, String kind, Instant since) {
            return entries.stream()
                    .filter(entry -> entry.owner().equals(owner) && entry.kind().equals(kind))
                    .toList();
        }

        @Override
        public boolean review(String owner, String id, String status, String actor, Instant at, String reason) {
            for (int i = 0; i < entries.size(); i++) {
                LearningEntry entry = entries.get(i);
                if (entry.owner().equals(owner) && entry.id().equals(id) && entry.status().equals("PENDING")) {
                    entries.set(i, entry.reviewed(status, actor, at, reason));
                    return true;
                }
            }
            return false;
        }


        @Override
        public java.util.Optional<String> ownerOf(String id) {
            return entries.stream().filter(entry -> entry.id().equals(id))
                    .map(LearningEntry::owner).findFirst();
        }

        @Override
        public boolean replace(LearningEntry replacement, String expectedStatus) {
            for (int i = 0; i < entries.size(); i++) {
                var entry = entries.get(i);
                if (entry.id().equals(replacement.id()) && entry.owner().equals(replacement.owner())
                        && entry.status().equals(expectedStatus)) {
                    entries.set(i, replacement); return true;
                }
            }
            return false;
        }

        @Override
        public boolean delete(String owner, String id) {
            return entries.removeIf(entry -> entry.owner().equals(owner) && entry.id().equals(id));
        }

        @Override
        public java.util.List<LearningEntry> pending(String kind) {
            return entries.stream()
                    .filter(entry -> kind.equals(entry.kind()) && "PENDING".equals(entry.status()))
                    .toList();
        }

        @Override
        public boolean recordRead(String id, Instant at) {
            for (int i = 0; i < entries.size(); i++) {
                LearningEntry entry = entries.get(i);
                if (entry.id().equals(id) && "APPROVED".equals(entry.status())) {
                    entries.set(i, entry.withUsage(entry.reads() + 1, at));
                    return true;
                }
            }
            return false;
        }
    }
}
