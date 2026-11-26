// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors
package com.kex.agent.skills;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kex.agent.mcp.McpResourceContent;
import com.kex.agent.mcp.McpToolCatalog;
import com.kex.agent.memory.LearningEntry;
import com.kex.agent.memory.SkillScope;

/**
 * Importe les compétences qu'un serveur MCP publie en ressources — la convention de Kafka SQL
 * Explorer : un catalogue {@value #CATALOGUE} (nom, description, URI et empreinte SHA-256 de chaque
 * {@code SKILL.md}) et un gabarit {@code skill://{name}/SKILL.md} qui rend les octets publiés.
 *
 * <p>Lire une ressource n'active rien. L'Explorer le dit lui-même : le chargement et le consentement
 * appartiennent à l'hôte. Chaque compétence lue entre donc en revue {@code PENDING}, comme une
 * procédure proposée à la main, et n'atteint le prompt qu'après l'approbation d'un humain nommé.
 * L'import ne contourne pas la revue : il lui épargne la recopie.
 *
 * <p>L'empreinte est recalculée sur les octets reçus. Un texte qui ne correspond pas à son manifeste
 * — altéré en route, ou republié sans que le catalogue suive — est refusé plutôt que proposé : le
 * réviseur approuverait un texte que l'éditeur n'a pas publié.
 *
 * <p>Les compétences d'ingénierie de l'Explorer ({@code kafkaexplorer-…} : réparer sa CI, publier sa
 * version) parlent du dépôt de l'Explorer, pas d'un cluster : elles sont écartées, avec leur motif.
 */
public final class McpSkillImporter {
    static final String CATALOGUE = "kex://skills";
    static final String ENGINEERING_PREFIX = "kafkaexplorer-";
    private static final Pattern NAME = Pattern.compile("[a-z0-9][a-z0-9-]{0,63}");
    private static final int MAX_MARKDOWN = 12000;

    public enum Status {
        /** Entrée en revue : rien n'agit avant l'approbation. */
        PROPOSED,
        /** Le même texte est déjà en revue ou approuvé : rien de nouveau à trancher. */
        KNOWN,
        /** Un humain a déjà écarté exactement ce texte. */
        JUDGED,
        /** Sans objet pour Kex (compétence d'ingénierie de l'éditeur). */
        SKIPPED,
        /** Illisible, hors limites ou empreinte différente du manifeste. */
        REFUSED
    }

    public record Outcome(String name, String uri, String digest, Status status, String detail, String skillId) {
    }

    public record Report(String connection, List<Outcome> outcomes) {
        public Report {
            outcomes = List.copyOf(outcomes);
        }

        public long proposed() {
            return outcomes.stream().filter(outcome -> outcome.status() == Status.PROPOSED).count();
        }
    }

    private final McpToolCatalog catalog;
    private final SkillsService skills;
    private final ObjectMapper json;
    private final Clock clock;

    public McpSkillImporter(McpToolCatalog catalog, SkillsService skills, ObjectMapper json, Clock clock) {
        this.catalog = catalog;
        this.skills = skills;
        this.json = json;
        this.clock = clock;
    }

    public Report importFrom(String owner, String connection) {
        return importFrom(owner, connection, java.util.Set.of());
    }

    /**
     * @param names les compétences à importer, par leur nom au catalogue ; vide pour toutes. Un nom
     *              absent du catalogue est signalé {@link Status#REFUSED}, pas ignoré : l'humain qui
     *              l'a demandé croirait sinon l'avoir proposé.
     */
    public Report importFrom(String owner, String connection, java.util.Set<String> names) {
        JsonNode entries = catalogue(connection);
        java.util.Set<String> wanted = names == null ? java.util.Set.of() : names;
        java.util.Set<String> seen = new java.util.HashSet<>();
        List<Outcome> outcomes = new ArrayList<>();
        for (JsonNode entry : entries) {
            String name = entry.path("frontmatter").path("name").asText("");
            seen.add(name);
            if (wanted.isEmpty() || wanted.contains(name)) outcomes.add(importOne(owner, connection, entry));
        }
        wanted.stream().filter(name -> !seen.contains(name)).sorted().forEach(name -> outcomes.add(
                new Outcome(name, null, null, Status.REFUSED, "Absente du catalogue " + CATALOGUE, null)));
        return new Report(connection, outcomes);
    }

    private JsonNode catalogue(String connection) {
        String text;
        try {
            text = text(catalog.readResource(connection, CATALOGUE));
        }
        catch (RuntimeException ex) {
            // Un serveur sans catalogue répond par une erreur de protocole, pas par une liste vide :
            // ce n'est pas « aucune compétence », c'est « pas de catalogue ici ».
            if (ex instanceof com.kex.agent.mcp.UnknownMcpServerException
                    || ex instanceof com.kex.agent.mcp.UnsupportedMcpCapabilityException
                    || ex instanceof com.kex.agent.mcp.McpServerUnavailableException) {
                throw ex;
            }
            throw new IllegalStateException("« " + connection + " » ne publie pas de catalogue " + CATALOGUE
                    + " : " + ex.getMessage(), ex);
        }
        try {
            JsonNode node = json.readTree(text == null ? "" : text);
            if (node == null || !node.isArray()) throw new IllegalStateException(CATALOGUE + " n'est pas une liste");
            return node;
        }
        catch (JsonProcessingException ex) {
            throw new IllegalStateException(CATALOGUE + " illisible sur « " + connection + " »", ex);
        }
    }

    private Outcome importOne(String owner, String connection, JsonNode entry) {
        String name = entry.path("frontmatter").path("name").asText("");
        String description = entry.path("frontmatter").path("description").asText("");
        String uri = entry.path("uri").asText("");
        JsonNode file = manifest(entry, uri);
        String digest = file == null ? null : file.path("digest").asText(null);
        if (!NAME.matcher(name).matches() || !uri.equals("skill://" + name + "/SKILL.md")) {
            return new Outcome(name, uri, digest, Status.REFUSED, "Nom ou URI hors de la convention skill://{nom}/SKILL.md", null);
        }
        if (name.startsWith(ENGINEERING_PREFIX)) {
            return new Outcome(name, uri, digest, Status.SKIPPED,
                    "Compétence d'ingénierie de l'éditeur, sans objet pour un cluster", null);
        }
        if (digest == null || !digest.startsWith("sha256:")) {
            return new Outcome(name, uri, digest, Status.REFUSED, "Manifeste sans empreinte SHA-256", null);
        }
        String markdown;
        try {
            markdown = text(catalog.readResource(connection, uri));
        }
        catch (RuntimeException ex) {
            return new Outcome(name, uri, digest, Status.REFUSED, "Lecture impossible : " + ex.getMessage(), null);
        }
        if (markdown == null || markdown.isBlank()) {
            return new Outcome(name, uri, digest, Status.REFUSED, "Ressource vide ou non textuelle", null);
        }
        byte[] bytes = markdown.getBytes(StandardCharsets.UTF_8);
        String actual = sha256(bytes);
        if (!actual.equals(digest) || file.has("size") && file.path("size").asLong() != bytes.length) {
            return new Outcome(name, uri, digest, Status.REFUSED,
                    "Empreinte différente du manifeste (reçu " + actual + ", " + bytes.length + " octets)", null);
        }
        if (markdown.strip().length() > MAX_MARKDOWN) {
            return new Outcome(name, uri, digest, Status.REFUSED, "Plus de " + MAX_MARKDOWN + " caractères", null);
        }
        boolean known = skills.list(owner).stream().anyMatch(existing -> existing.markdown().equals(markdown.strip())
                && ("PENDING".equals(existing.status()) || "APPROVED".equals(existing.status())));
        if (known) {
            return new Outcome(name, uri, digest, Status.KNOWN, "Déjà en revue ou approuvée", null);
        }
        try {
            LearningEntry proposed = skills.propose(owner, title(name, description), markdown,
                    evidence(connection, uri, digest), null, null, SkillScope.DOMAIN, triggers(name));
            return new Outcome(name, uri, digest, Status.PROPOSED, "En revue", proposed.id());
        }
        catch (SkillAlreadyJudgedException ex) {
            return new Outcome(name, uri, digest, Status.JUDGED, "Déjà écartée par un humain", null);
        }
    }

    private static JsonNode manifest(JsonNode entry, String uri) {
        for (JsonNode file : entry.path("resources")) {
            if (uri.equals(file.path("uri").asText())) return file;
        }
        return null;
    }

    private static String title(String name, String description) {
        String title = description.isBlank() ? name : name + " — " + description;
        return title.length() <= 200 ? title : title.substring(0, 199) + "…";
    }

    private String evidence(String connection, String uri, String digest) {
        return "Publiée par le serveur MCP « " + connection + " » : " + uri + ", " + digest + ", lue le "
                + clock.instant() + ". Empreinte vérifiée sur les octets reçus ; une lecture de ressource "
                + "n'active rien, l'approbation reste humaine.";
    }

    /**
     * Les mots du nom, sans « kafka » : il figure dans presque chaque demande adressée à Kex, et une
     * compétence qu'il déclencherait agirait partout. Sans déclencheur, une compétence de domaine
     * pèserait sur toutes les conversations ; avec ceux-là, elle n'agit que sur son sujet.
     *
     * <p>Les noms sont anglais et les demandes souvent françaises. Les accents ne comptent pas à la
     * comparaison (« schema » trouve « schéma ») ; seuls les mots qui changent vraiment de forme
     * reçoivent leur équivalent français.
     */
    static List<String> triggers(String name) {
        return Arrays.stream(name.split("-"))
                .map(word -> word.toLowerCase(Locale.ROOT))
                .filter(word -> word.length() >= 4 && !word.equals("kafka"))
                .flatMap(word -> FRENCH.containsKey(word) ? java.util.stream.Stream.of(word, FRENCH.get(word))
                        : java.util.stream.Stream.of(word))
                .distinct().limit(10).toList();
    }

    private static final java.util.Map<String, String> FRENCH = java.util.Map.of(
            "forecast", "prévision", "review", "revue", "data", "données", "analyst", "analyse");

    private static String text(List<McpResourceContent> contents) {
        if (contents == null) return null;
        return contents.stream().map(McpResourceContent::text).filter(text -> text != null).findFirst().orElse(null);
    }

    private static String sha256(byte[] bytes) {
        try {
            return "sha256:" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        }
        catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
    }
}
