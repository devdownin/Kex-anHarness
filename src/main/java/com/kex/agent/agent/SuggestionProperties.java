// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors
package com.kex.agent.agent;

import java.time.Duration;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.hibernate.validator.constraints.time.DurationMin;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * Les questions de relance que la console propose sous une réponse du chat.
 *
 * <p>Débrayables à deux endroits, qui ne disent pas la même chose : {@code enabled} ici retire la
 * route et l'appel au modèle pour toute l'installation (la console ne montre alors pas même
 * l'interrupteur), et l'interrupteur de la console les coupe pour un navigateur, sans appel non plus.
 * Un appel au modèle par réponse n'est pas gratuit : il compte dans le budget de jetons de l'appelant
 * et dans sa limite de débit, comme un échange.
 */
@ConfigurationProperties("kex.agent.suggestions")
@Validated
public record SuggestionProperties(
        /** Faux : ni route, ni appel au modèle, ni interrupteur dans la console. */
        @DefaultValue("true") boolean enabled,
        /** Au plus ce nombre de suggestions, de 1 à 3. */
        @DefaultValue("3") @Min(1) @Max(3) int max,
        /** Au-delà, pas de suggestion plutôt qu'une attente : elles ne sont jamais la réponse. */
        @DefaultValue("30s") @DurationMin(seconds = 1) Duration timeout,
        /** Ce qui est relu de la réponse : sa fin compte moins que son début pour proposer une suite. */
        @DefaultValue("6000") @Min(200) int maxAnswerCharacters) {
}
