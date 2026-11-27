// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors
package com.kex.agent.config;

import java.io.IOException;
import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.ai.model.openai.autoconfigure.AbstractOpenAiProperties;
import org.springframework.ai.model.openai.autoconfigure.OpenAiAutoConfigurationUtil;
import org.springframework.ai.model.openai.autoconfigure.OpenAiChatProperties;
import org.springframework.ai.model.openai.autoconfigure.OpenAiCommonProperties;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.bind.PropertySourcesPlaceholdersResolver;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Ce que le client HTTP du fournisseur {@code openai} reçoit réellement, lu dans les YAML livrés et
 * résolu par la même fonction que l'autoconfiguration de Spring AI.
 *
 * <p>Le client de Spring AI est borné par {@code spring.ai.openai.timeout} (60 s par défaut) — un
 * plafond sur l'<em>appel entier</em>, flux compris, pas sur le premier octet — et réessaie
 * {@code max-retries} fois (3 par défaut), en rejouant le prompt complet. Les profils {@code ollama}
 * et {@code vllm} posaient {@code max-retries: 0} sous {@code chat.options.} : cette clé est un alias
 * de compatibilité qui garde ses champs de <em>connexion</em> pour elle et que rien ne lit, si bien
 * que le réglage ne faisait rien et qu'un modèle local lent était interrompu à 60 s puis rejoué
 * trois fois. Le test lit la valeur résolue, jamais la clé.
 */
class LlmConnectionConfigTest {

    private static final YamlPropertySourceLoader LOADER = new YamlPropertySourceLoader();

    private static Duration bound(String key, String... yamlFiles) throws IOException {
        MutablePropertySources sources = sources(yamlFiles);
        return new Binder(ConfigurationPropertySources.from(sources), new PropertySourcesPlaceholdersResolver(sources))
                .bind(key, Duration.class).orElseThrow(() -> new IllegalStateException(key + " n'est posé nulle part"));
    }

    private static MutablePropertySources sources(String... yamlFiles) throws IOException {
        MutablePropertySources sources = new MutablePropertySources();
        // Dernier fichier = plus prioritaire, comme un profil actif par-dessus application.yml.
        for (String file : yamlFiles) {
            List<PropertySource<?>> loaded = LOADER.load(file, new ClassPathResource(file));
            loaded.forEach(sources::addFirst);
        }
        // Les variables d'environnement par défaut du dépôt : aucune n'est posée.
        sources.addLast(new MapPropertySource("vide", java.util.Map.of()));
        return sources;
    }

    private static AbstractOpenAiProperties resolved(String... yamlFiles) throws IOException {
        MutablePropertySources sources = sources(yamlFiles);
        Binder binder = new Binder(ConfigurationPropertySources.from(sources),
                new PropertySourcesPlaceholdersResolver(sources));
        OpenAiCommonProperties common = binder.bind("spring.ai.openai", OpenAiCommonProperties.class)
                .orElseGet(OpenAiCommonProperties::new);
        OpenAiChatProperties chat = binder.bind("spring.ai.openai.chat", OpenAiChatProperties.class)
                .orElseGet(OpenAiChatProperties::new);
        return OpenAiAutoConfigurationUtil.resolveCommonProperties(common, chat);
    }

    @Test
    void le_fournisseur_heberge_laisse_a_un_appel_au_moins_la_duree_d_une_reponse_longue() throws IOException {
        AbstractOpenAiProperties openai = resolved("application.yml");

        // 60 s coupaient une réponse de 4 096 jetons à 40 jet/s (~100 s), flux compris.
        assertThat(openai.getTimeout()).isGreaterThanOrEqualTo(Duration.ofSeconds(120));
        // Jamais au-delà de ce que l'échange entier peut durer : un appel plus long que lui ne finirait pas.
        assertThat(openai.getTimeout()).isLessThanOrEqualTo(Duration.ofMinutes(5));
    }

    @Test
    void les_profils_locaux_ne_rejouent_pas_un_prompt_complet_sur_un_modele_deja_lent() throws IOException {
        for (String profile : new String[] {"application-ollama.yml", "application-vllm.yml"}) {
            AbstractOpenAiProperties openai = resolved("application.yml", profile);

            assertThat(openai.getMaxRetries()).as(profile + " : max-retries").isZero();
            // Un CPU met des minutes sur un prompt de plusieurs milliers de jetons.
            assertThat(openai.getTimeout()).as(profile + " : timeout").isGreaterThanOrEqualTo(Duration.ofMinutes(5));
        }
    }

    @Test
    void l_echange_dure_toujours_plus_longtemps_que_l_appel_qu_il_porte() throws IOException {
        // Un appel plus long que l'échange ne pourrait pas finir ; l'échange plus long que la requête
        // asynchrone de Spring MVC se verrait couper par elle avant d'avoir rendu son propre message.
        for (String[] files : new String[][] {{"application.yml"}, {"application.yml", "application-ollama.yml"},
                {"application.yml", "application-vllm.yml"}}) {
            Duration call = resolved(files).getTimeout();
            Duration exchange = bound("kex.agent.request-timeout", files);
            Duration mvc = bound("spring.mvc.async.request-timeout", files);

            assertThat(exchange).as(String.join("+", files) + " : échange").isGreaterThanOrEqualTo(call);
            assertThat(mvc).as(String.join("+", files) + " : requête asynchrone").isGreaterThan(exchange);
        }
    }
}
