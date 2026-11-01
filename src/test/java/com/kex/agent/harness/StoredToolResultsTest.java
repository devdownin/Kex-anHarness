// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors
package com.kex.agent.harness;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.chat.model.ToolContext;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class StoredToolResultsTest {
    @TempDir Path directory;
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private final Instant now = Instant.parse("2026-11-01T00:00:00Z");

    @Test
    void relit_le_milieu_sans_rappeler_loutil_et_retrouve_apres_redemarrage() {
        Path file = directory.resolve("results.json");
        var props = HarnessFixtures.properties(file.toString());
        var repository = new FileToolResultRepository(mapper, file);
        var results = new StoredToolResults(props, repository, Clock.fixed(now, ZoneOffset.UTC));
        var context = new ToolContext(HarnessFixtures.context("tenant", "conversation:roles"));
        String id = results.offload("read_lag", "{}", "debut" + "x".repeat(500) + "DECISIF" + "y".repeat(500), context);
        var restarted = new StoredToolResults(props, new FileToolResultRepository(mapper, file), Clock.fixed(now, ZoneOffset.UTC));
        var page = restarted.read(id, 505, 7, context, r -> assertThat(r.tool()).isEqualTo("read_lag"));
        assertThat(page.content()).isEqualTo("DECISIF");
        assertThat(page.nextOffset()).isEqualTo(512);
        assertThat(page.hasMore()).isTrue();
        assertThat(page.sha256()).isEqualTo(ConversationLock.hash("debut" + "x".repeat(500) + "DECISIF" + "y".repeat(500)));
    }
    @Test
    void cloisonne_les_locataires_conversations_roles_et_les_expirations() {
        var props = HarnessFixtures.properties(directory.resolve("results.json").toString());
        var repository = new FileToolResultRepository(mapper, Path.of(props.results().storePath()));
        var results = new StoredToolResults(props, repository, Clock.fixed(now, ZoneOffset.UTC));
        var context = new ToolContext(HarnessFixtures.context("tenant", "conv:OPERATOR"));
        String id = results.offload("read_lag", "{}", "contenu", context);
        for (var other : new ToolContext[] {
                new ToolContext(HarnessFixtures.context("other", "conv:OPERATOR")),
                new ToolContext(HarnessFixtures.context("tenant", "other:OPERATOR")),
                new ToolContext(HarnessFixtures.context("tenant", "conv:CHAT")) }) {
            assertThatThrownBy(() -> results.read(id, 0, 10, other, r -> { })).hasMessage("Résultat indisponible ou expiré");
        }
        var expired = new StoredToolResults(props, repository, Clock.fixed(now.plusSeconds(600), ZoneOffset.UTC));
        assertThatThrownBy(() -> expired.read(id, 0, 10, context, r -> { })).hasMessageContaining("expiré");
    }
    @Test
    void applique_quotas_purge_et_refus_de_droits_courants() {
        var props = HarnessFixtures.properties(directory.resolve("results.json").toString());
        var results = new StoredToolResults(props, new FileToolResultRepository(mapper, Path.of(props.results().storePath())),
                Clock.fixed(now, ZoneOffset.UTC));
        var context = new ToolContext(HarnessFixtures.context("tenant", "conv"));
        String id = results.offload("read", "{}", "premier", context);
        assertThat(results.offload("read", "{}", "second", context)).isNotNull();
        assertThat(results.offload("read", "{}", "troisieme", context)).isNull();
        assertThatThrownBy(() -> results.read(id, 0, 10, context, r -> { throw new SecurityException("révoqué"); }))
                .isInstanceOf(SecurityException.class);
        results.clear("tenant", "conv");
        assertThat(results.offload("read", "{}", "nouveau", context)).isNotNull();
        assertThatThrownBy(() -> results.read(id, 0, 10, context, r -> { })).hasMessageContaining("indisponible");
    }
    @Test
    void ne_stocke_pas_une_reference_que_lechange_ne_peut_pas_relire() {
        var props = HarnessFixtures.properties(directory.resolve("results.json").toString());
        var results = new StoredToolResults(props, new FileToolResultRepository(mapper, Path.of(props.results().storePath())),
                Clock.fixed(now, ZoneOffset.UTC));
        var values = HarnessFixtures.context("tenant", "conv");
        values.put(com.kex.agent.agent.TaskToolPolicy.ALLOWED_TOOLS, java.util.Set.of("read"));
        assertThat(results.offload("read", "{}", "contenu complet", new ToolContext(values))).isNull();
    }

    @Test
    void pages_bornees_unicode_et_positions_invalides() {
        var props = HarnessFixtures.properties(directory.resolve("results.json").toString());
        var results = new StoredToolResults(props, new FileToolResultRepository(mapper, Path.of(props.results().storePath())),
                Clock.fixed(now, ZoneOffset.UTC));
        var context = new ToolContext(HarnessFixtures.context("tenant", "conv"));
        String id = results.offload("read", "{}", "a😀b", context);
        assertThat(results.read(id, 1, 2, context, r -> { }).content()).isEqualTo("😀");
        assertThat(results.read(id, 2, 2, context, r -> { }).content()).isEqualTo("😀");
        assertThatThrownBy(() -> results.read(id, 1, 1, context, r -> { })).hasMessageContaining("Unicode");
        assertThatThrownBy(() -> results.read(id, 0, 1001, context, r -> { })).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> results.read(id, -1, 2, context, r -> { })).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> results.read(id, 9, 2, context, r -> { })).isInstanceOf(IllegalArgumentException.class);
    }
}
