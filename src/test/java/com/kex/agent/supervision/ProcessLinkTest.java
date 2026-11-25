// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors
package com.kex.agent.supervision;

import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Les liens d'un processus mènent un humain vers l'application qui le montre de près. Servis dans
 * la console, ils ne doivent jamais pouvoir exécuter de script au clic.
 */
class ProcessLinkTest {

    @Test
    void refuse_tout_schema_autre_que_http_et_https() {
        assertThatThrownBy(() -> new ProcessLink("Topic", "javascript:alert(1)"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ProcessLink("Topic", "/topic/toll-events"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ProcessLink(" ", "https://explorer.localhost/topic/toll-events"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(new ProcessLink(" Topic ", "http://localhost:8080/topic/toll-events").label()).isEqualTo("Topic");
    }

    /** La suite déclare ses processus par variables d'environnement indexées : les liens aussi. */
    @Test
    void se_lie_depuis_des_proprietes_indexees() {
        Map<String, String> source = Map.of(
                "kex.agent.supervision.processes[0].id", "pipeline-peage",
                "kex.agent.supervision.processes[0].name", "Pipeline péage",
                "kex.agent.supervision.processes[0].links[0].label", "Topic dans KafkaExplorer",
                "kex.agent.supervision.processes[0].links[0].url", "http://localhost:8080/topic/toll-events");
        List<MonitoredProcess> processes = new Binder(new MapConfigurationPropertySource(source))
                .bind("kex.agent.supervision.processes", Bindable.listOf(MonitoredProcess.class)).get();

        assertThat(processes.getFirst().links()).containsExactly(
                new ProcessLink("Topic dans KafkaExplorer", "http://localhost:8080/topic/toll-events"));
    }

    /** Un fichier de processus écrit avant les liens se relit sans erreur, avec une liste vide. */
    @Test
    void un_processus_enregistre_sans_liens_se_relit() throws Exception {
        MonitoredProcess read = new ObjectMapper().readValue(
                "{\"id\":\"p\",\"name\":\"P\",\"description\":null,\"hint\":null,\"thresholds\":null}",
                MonitoredProcess.class);

        assertThat(read.links()).isEmpty();
    }
}
