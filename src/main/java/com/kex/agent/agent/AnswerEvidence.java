// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors
package com.kex.agent.agent;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Les chiffres d'une réponse de chat se retrouvent-ils dans ce que les outils ont réellement rendu ?
 *
 * <p>Le cycle de supervision écarte déjà une anomalie qui ne cite aucun outil abouti : une affirmation
 * n'est pas une mesure. Le chat n'avait pas cet équivalent — un retard de « 120 000 messages » inventé
 * se lisait comme un retard mesuré. Le contrôle reste déterministe, comme celui du cycle : un second
 * modèle chargé de juger le premier hériterait de son biais optimiste, et il coûterait un appel de
 * plus à chaque réponse.
 *
 * <p>C'est une <strong>alerte</strong>, jamais un refus, et elle ne dit que ce qu'elle peut établir :
 * <ul>
 *   <li>un chiffre n'est cherché que s'il a de quoi être une mesure (trois chiffres ou plus), qu'il
 *       n'est ni une date ni une année, et que l'utilisateur ne l'a pas écrit lui-même ;</li>
 *   <li>la comparaison ne garde que les chiffres : « 120 000 », « 120000 » et « 120,000 » sont le même
 *       nombre, et un séparateur décimal ne fait pas manquer une mesure — au prix de laisser passer
 *       « 1,5 » pour « 15 ». Mieux vaut un oubli qu'une fausse accusation ;</li>
 *   <li>quand un résultat d'outil n'a pas pu être relu (texte libre, trop long : {@code result} est
 *       {@code null}), l'absence d'un chiffre ne prouve rien : {@link Status#UNVERIFIABLE}, pas
 *       {@link Status#UNSUPPORTED}.</li>
 * </ul>
 * Une somme ou un ratio calculé par le modèle à partir de mesures n'est pas retrouvé tel quel : c'est
 * le faux positif connu, et le libellé le dit (« non retrouvés tels quels »).
 */
public final class AnswerEvidence {

    public enum Status {
        /** Aucun chiffre pouvant être une mesure : rien à contrôler. */
        NOT_APPLICABLE,
        /** Chaque chiffre cité se retrouve dans un résultat d'outil. */
        GROUNDED,
        /** Des chiffres ne se retrouvent dans aucun résultat d'outil, ou aucun outil n'a abouti. */
        UNSUPPORTED,
        /** Des chiffres manquent, mais un résultat n'a pas pu être relu : on ne conclut pas. */
        UNVERIFIABLE
    }

    /**
     * @param status      le constat
     * @param toolCalls   appels d'outil de l'échange
     * @param unsupported les chiffres cités tels qu'écrits, sans doublon, dans l'ordre de la réponse
     * @param message     le constat lisible tel que la console l'affiche, vide s'il n'y a rien à dire
     */
    public record Evidence(Status status, int toolCalls, List<String> unsupported, String message) {
        public Evidence {
            unsupported = List.copyOf(unsupported);
        }

        static Evidence of(Status status, int toolCalls, List<String> unsupported) {
            return new Evidence(status, toolCalls, unsupported, describe(status, toolCalls, unsupported));
        }

        /** Seuls les constats qui disent quelque chose sont signalés à l'utilisateur. */
        public boolean worthSaying() {
            return status == Status.UNSUPPORTED || status == Status.UNVERIFIABLE;
        }
    }

    // Un nombre : des groupes de trois chiffres séparés par une espace (y compris insécable), ou des
    // chiffres reliés par un seul point ou une seule virgule. Une virgule suivie d'une espace ne relie
    // rien — « en 2026, 12 partitions » fait deux nombres —, et une espace ne relie que des milliers.
    private static final Pattern NUMBER = Pattern.compile(
            "(?<![\\p{L}\\d_.-])(?:\\d{1,3}(?:[\\u00a0\\u202f ]\\d{3})+(?:[.,]\\d+)?|\\d+(?:[.,]\\d+)*)(?![\\p{L}\\d_])");
    // Dates ISO, françaises ou anglaises : leurs chiffres ne sont pas des mesures.
    private static final Pattern DATE = Pattern.compile(
            "\\b\\d{4}-\\d{2}-\\d{2}(?:[T ]\\d{2}:\\d{2}(?::\\d{2})?(?:\\.\\d+)?Z?)?\\b|\\b\\d{1,2}[/.]\\d{1,2}[/.]\\d{2,4}\\b|\\b\\d{1,2}:\\d{2}(?::\\d{2})?\\b");
    private static final int MINIMUM_DIGITS = 3;

    private AnswerEvidence() {
    }

    public static Evidence check(String answer, String userMessage, List<AgentEvent.ToolCall> calls) {
        List<AgentEvent.ToolCall> all = calls == null ? List.of() : calls;
        Map<String, String> cited = figures(answer);
        Set<String> given = figures(userMessage).keySet();
        cited.keySet().removeAll(given);
        if (cited.isEmpty()) {
            return Evidence.of(Status.NOT_APPLICABLE, all.size(), List.of());
        }

        List<AgentEvent.ToolCall> succeeded = all.stream().filter(call -> !call.failed()).toList();
        Set<String> observed = new HashSet<>();
        boolean unreadable = false;
        for (AgentEvent.ToolCall call : succeeded) {
            if (call.result() == null) {
                unreadable = true;
            }
            else {
                // Les nombres complets seulement, jamais un préfixe : le « 12 » d'un résultat ne
                // couvre pas le « 120 » d'une réponse.
                observed.addAll(figures(call.result()).keySet());
            }
        }
        List<String> missing = new ArrayList<>();
        cited.forEach((digits, written) -> {
            if (!observed.contains(digits)) missing.add(written);
        });
        if (missing.isEmpty()) {
            return Evidence.of(Status.GROUNDED, all.size(), List.of());
        }
        if (unreadable) {
            return Evidence.of(Status.UNVERIFIABLE, all.size(), missing);
        }
        return Evidence.of(Status.UNSUPPORTED, all.size(), missing);
    }

    /** Chiffres normalisés → première écriture, pour les nombres qui ont de quoi être une mesure. */
    private static Map<String, String> figures(String text) {
        Map<String, String> found = new LinkedHashMap<>();
        if (text == null || text.isBlank()) return found;
        String withoutDates = DATE.matcher(text).replaceAll(" ");
        Matcher matcher = NUMBER.matcher(withoutDates);
        while (matcher.find()) {
            String written = matcher.group().strip().replaceAll("[.,]+$", "");
            String digits = written.replaceAll("\\D", "");
            if (digits.length() < MINIMUM_DIGITS || isYear(digits)) continue;
            found.putIfAbsent(digits, written);
        }
        return found;
    }

    // 1990 à 2099 : une année citée dans une phrase n'est pas un retard.
    private static boolean isYear(String digits) {
        return digits.length() == 4 && (digits.startsWith("19") || digits.startsWith("20"))
                && Integer.parseInt(digits) >= 1990 && Integer.parseInt(digits) <= 2099;
    }

    private static String describe(Status status, int toolCalls, List<String> unsupported) {
        return switch (status) {
            case UNSUPPORTED -> toolCalls == 0
                    ? "Chiffres cités sans qu'aucun outil n'ait été appelé : " + String.join(", ", unsupported)
                    : "Chiffres non retrouvés tels quels dans les résultats des outils : " + String.join(", ", unsupported);
            case UNVERIFIABLE -> "Chiffres non vérifiables : un résultat d'outil n'a pas pu être relu";
            case GROUNDED, NOT_APPLICABLE -> "";
        };
    }
}
