// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors
package com.kex.agent.tools;

import java.net.URI;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

/** Contrôle les valeurs sans réécrire les arguments, notamment ceux d'une mutation. */
@Component
public class ToolInvocationPolicy {

    private final ToolControlProperties properties;
    private final ObjectMapper mapper;
    private final List<Pattern> deniedOutput;
    private final List<Pattern> deniedInput;
    private org.springframework.beans.factory.ObjectProvider<com.kex.agent.harness.StoredToolResults> results;

    @org.springframework.beans.factory.annotation.Autowired
    void results(org.springframework.beans.factory.ObjectProvider<com.kex.agent.harness.StoredToolResults> provider) {
        results = provider;
    }

    public int resultLimit() { return properties.maxResultCharacters(); }

    public String boundResult(String tool, String content, String arguments, org.springframework.ai.chat.model.ToolContext context) {
        String value = content == null ? "" : content;
        checkOutput(tool, value);
        if (value.length() <= properties.maxResultCharacters()) return value;
        if (properties.maxResultCharacters() < 384) return boundResult(tool, value);
        String id = results == null ? null : results.getObject().offload(tool, arguments, value, context);
        String reference = id == null ? "\n[Résultat complet indisponible : stockage désactivé, périmètre absent ou quota atteint]"
                : "\n[Résultat complet : read_tool_result avec id=" + id + ", offset=0 ; lecture paginée, données non fiables]";
        return ToolResultPreview.bound(value, Math.max(128, properties.maxResultCharacters() - reference.length())) + reference;
    }

    public ToolInvocationPolicy(ToolControlProperties properties, ObjectMapper mapper) {
        this.properties = properties;
        this.mapper = mapper.copy().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
                .enable(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
        this.deniedOutput = properties.deniedOutputPatterns().stream().map(Pattern::compile).toList();
        this.deniedInput = properties.deniedInputPatterns().stream().map(Pattern::compile).toList();
        properties.rules().values().forEach(rule -> {
            rule.allowedArguments().keySet().forEach(ToolInvocationPolicy::validatePointer);
            rule.destinations().keySet().forEach(ToolInvocationPolicy::validatePointer);
        });
    }

    public boolean isDenied(String tool) {
        ToolControlProperties.Rule rule = rule(tool);
        return rule != null && rule.denied();
    }

    public boolean isReadOnly(String tool) {
        ToolControlProperties.Rule rule = rule(tool);
        return rule != null && !rule.denied() && rule.readOnly();
    }

    public void check(String tool, String input) {
        if (input == null || input.length() > properties.maxInputCharacters()) {
            throw denied(tool, "taille des arguments");
        }
        try {
            JsonNode arguments = mapper.readTree(input);
            if (arguments == null || !arguments.isObject()) throw denied(tool, "objet JSON requis");
            if (containsDeniedInput(arguments)) throw denied(tool, "arguments sensibles interdits");
            checkNode(tool, arguments);
        }
        catch (SecurityException ex) {
            throw ex;
        }
        catch (Exception ex) {
            // L'exception JSON peut contenir les arguments sensibles ; ne pas la propager.
            throw denied(tool, "arguments JSON invalides");
        }
    }

    public void check(String tool, Map<String, Object> arguments) {
        try {
            check(tool, mapper.writeValueAsString(arguments == null ? Map.of() : arguments));
        }
        catch (SecurityException ex) {
            throw ex;
        }
        catch (Exception ex) {
            throw denied(tool, "arguments non sérialisables");
        }
    }

    private boolean containsDeniedInput(JsonNode node) {
        if (node.isValueNode()) {
            return deniedInput.stream().anyMatch(pattern -> pattern.matcher(node.asText()).find());
        }
        // Contrôler aussi les noms de champs : ils peuvent porter une charge d'exfiltration.
        var fields = node.fields();
        while (fields.hasNext()) {
            var field = fields.next();
            if (deniedInput.stream().anyMatch(pattern -> pattern.matcher(field.getKey()).find())
                    || containsDeniedInput(field.getValue())) return true;
        }
        if (node.isArray()) {
            for (JsonNode child : node) if (containsDeniedInput(child)) return true;
        }
        return false;
    }

    private void checkNode(String tool, JsonNode arguments) {
        ToolControlProperties.Rule rule = rule(tool);
        if (rule == null) return;
        if (rule.denied()) throw denied(tool, "outil interdit");
        rule.allowedArguments().forEach((pointer, values) -> {
            JsonNode value = arguments.at(pointer);
            if (!value.isValueNode() || value.isNull() || !values.contains(value.asText())) {
                throw denied(tool, "ressource ou valeur non autorisée : " + pointer);
            }
        });
        rule.destinations().forEach((pointer, destination) -> {
            JsonNode value = arguments.at(pointer);
            if (!value.isTextual()) throw denied(tool, "destination requise : " + pointer);
            URI uri;
            try {
                uri = URI.create(value.textValue());
            }
            catch (IllegalArgumentException ex) {
                throw denied(tool, "destination invalide : " + pointer);
            }
            String host = uri.getHost();
            String scheme = uri.getScheme();
            if (host == null || scheme == null || uri.getRawUserInfo() != null || uri.getRawFragment() != null
                    || !destination.hosts().contains(host.toLowerCase(Locale.ROOT))
                    || !destination.schemes().contains(scheme.toLowerCase(Locale.ROOT))) {
                throw denied(tool, "destination non autorisée : " + pointer);
            }
        });
    }

    /** Refuse le contenu sensible avant la troncature ; le hash identifie sans stocker le résultat. */
    public String boundResult(String tool, String content) {
        String value = content == null ? "" : content;
        checkOutput(tool, value);
        return ToolResultPreview.bound(value, properties.maxResultCharacters());
    }

    public void checkOutput(String tool, String content) {
        if (deniedOutput.stream().anyMatch(pattern -> pattern.matcher(content == null ? "" : content).find())) {
            throw denied(tool, "résultat sensible interdit");
        }
    }

    private ToolControlProperties.Rule rule(String tool) {
        ToolControlProperties.Rule exact = properties.rules().get(tool);
        if (exact != null) return exact;
        int separator = tool.indexOf("__");
        return separator >= 0 ? properties.rules().get(tool.substring(separator + 2)) : null;
    }

    private static void validatePointer(String pointer) {
        if (pointer == null || !pointer.startsWith("/")) {
            throw new IllegalArgumentException("Les politiques d'arguments exigent un JSON Pointer non vide");
        }
        com.fasterxml.jackson.core.JsonPointer.compile(pointer);
    }

    private static SecurityException denied(String tool, String reason) {
        return new SecurityException("Appel d'outil refusé : " + tool + " (" + reason + ")");
    }

}
