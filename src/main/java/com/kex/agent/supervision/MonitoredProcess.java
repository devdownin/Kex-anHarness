// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors
package com.kex.agent.supervision;

/**
 * Un processus d'intégration à surveiller. Volontairement sans vocabulaire Kafka : le domaine
 * décrit ce qu'on surveille, les outils MCP décrivent comment on l'observe. {@code hint} est
 * transmis au modèle pour qu'il sache quels outils interroger pour ce processus précis.
 *
 * @param thresholds seuils propres à ce processus, {@code null} par défaut pour hériter des seuils
 *                   globaux de la politique en vigueur — voir {@link Thresholds#withOverrides}
 * @param links      où regarder ce processus de près, dans les autres applications — voir
 *                   {@link ProcessLink}. Jamais transmis au modèle : ce sont des adresses pour un humain
 */
public record MonitoredProcess(String id, String name, String description, String hint,
                               ThresholdOverrides thresholds, java.util.List<ProcessLink> links) {

    @org.springframework.boot.context.properties.bind.ConstructorBinding
    public MonitoredProcess {
        links = links == null ? java.util.List.of() : java.util.List.copyOf(links);
    }

    public MonitoredProcess(String id, String name, String description, String hint, ThresholdOverrides thresholds) {
        this(id, name, description, hint, thresholds, java.util.List.of());
    }
}
