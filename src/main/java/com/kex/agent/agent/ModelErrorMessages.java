// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors
package com.kex.agent.agent;

import com.anthropic.errors.AnthropicException;
import com.anthropic.errors.AnthropicServiceException;
import com.openai.errors.OpenAIException;
import com.openai.errors.OpenAIServiceException;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import org.slf4j.Logger;

/**
 * Traduit une panne d'appel au modèle en motif exploitable, sans jamais recopier le corps brut
 * renvoyé par le fournisseur (JSON, identifiant de requête ou de compte). Partagé entre la route
 * bloquante, le flux SSE du chat et l'espace utilisateur afin qu'une même panne se raconte dans les
 * mêmes termes partout.
 */
public final class ModelErrorMessages {

    public static final String MODEL_CIRCUIT_OPEN =
            "Le modèle a échoué à plusieurs reprises récemment ; nouvel essai dans quelques instants";

    private ModelErrorMessages() {
    }

    /**
     * Motif destiné à un événement SSE {@code error}, avec journalisation cohérente : les refus
     * attendus (délai, disjoncteur, budget) n'encombrent pas les journaux d'erreur, tandis que les
     * pannes amont ou inattendues y laissent leur trace complète.
     */
    public static String streamError(Throwable ex, Logger log, String fallback) {
        if (ex instanceof AgentTimeoutException timeout) {
            return timeout.getMessage();
        }
        if (ex instanceof CallNotPermittedException) {
            return MODEL_CIRCUIT_OPEN;
        }
        if (ex instanceof BudgetExceededException) {
            return ex.getMessage();
        }
        if (ex instanceof AnthropicException || ex instanceof OpenAIException) {
            log.warn("Le fournisseur du modèle a refusé ou n'a pas pu traiter l'appel en flux", ex);
            return modelProviderMessage(ex);
        }
        log.error("Échec pendant le flux de l'agent", ex);
        return fallback;
    }

    /**
     * Le motif sans le corps brut du fournisseur, souvent un JSON : le code HTTP amont ou la cause
     * réseau suffisent à dire où regarder. Le détail reste dans les journaux.
     */
    public static String modelProviderMessage(Throwable ex) {
        Integer status = ex instanceof AnthropicServiceException anthropic ? anthropic.statusCode()
                : ex instanceof OpenAIServiceException openai ? openai.statusCode() : null;
        if (status == null) {
            if (hasCause(ex, java.net.SocketTimeoutException.class, java.io.InterruptedIOException.class)) {
                return "Le fournisseur du modèle n'a pas répondu dans le délai accordé à un appel "
                        + "(spring.ai.openai.timeout, KEX_AGENT_MODEL_TIMEOUT) — un modèle local lent demande un plafond plus long";
            }
            if (hasCause(ex, java.net.ConnectException.class, java.net.UnknownHostException.class,
                    java.net.NoRouteToHostException.class)) {
                return "Le fournisseur du modèle est injoignable (connexion refusée ou adresse inconnue) — "
                        + "vérifier son adresse de base et qu'il tourne";
            }
            return "Le fournisseur du modèle est injoignable ou a renvoyé une réponse illisible";
        }
        return switch (status) {
            case 401, 403 -> "Le fournisseur du modèle a refusé la clé (HTTP " + status + ") — vérifier la clé dans Configuration";
            case 429 -> "Limite de débit du fournisseur du modèle atteinte (HTTP 429) — réessayer dans un instant";
            case 402 -> "Le fournisseur du modèle réclame des crédits que ce compte n'a pas (HTTP 402)";
            case 404 -> "Modèle ou adresse introuvable chez le fournisseur (HTTP 404) — vérifier le nom du modèle et l'adresse de base";
            case 400 -> "Le fournisseur du modèle a refusé la requête (HTTP 400) — souvent une fenêtre de contexte "
                    + "dépassée, ou un modèle qui ne sait pas appeler d'outils";
            case 408, 504 -> "Le fournisseur du modèle a mis trop de temps à répondre (HTTP " + status + ")";
            default -> status >= 500
                    ? "Le fournisseur du modèle est indisponible (HTTP " + status + ") — réessayer plus tard"
                    : "Le fournisseur du modèle n'a pas pu traiter l'appel (HTTP " + status + ")";
        };
    }

    @SafeVarargs
    private static boolean hasCause(Throwable ex, Class<? extends Throwable>... types) {
        int depth = 0;
        for (Throwable cause = ex; cause != null && depth < 8; cause = cause.getCause(), depth++) {
            for (Class<? extends Throwable> type : types) {
                if (type.isInstance(cause)) return true;
            }
        }
        return false;
    }
}
