package com.my.custom.claudepersonalassistant.mcp.domain.tool.google;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Builds a tool's JSON Schema with a stable property order.
 *
 * <p>{@code LinkedHashMap} rather than {@code Map.of} on purpose: {@code Map.of} randomises its
 * iteration order per JVM start, so an otherwise unchanged {@code tools/list} would serialise
 * differently after every restart — and a tool list that changes bytes without changing meaning is
 * exactly what invalidates a model prompt cache. The registry sorts the tools; this sorts inside
 * one.
 */
final class ToolSchema {

    private final Map<String, Object> properties = new LinkedHashMap<>();
    private final List<String> required = new ArrayList<>();

    private ToolSchema() {
    }

    static ToolSchema object() {
        return new ToolSchema();
    }

    ToolSchema required(String name, Map<String, Object> specification) {
        properties.put(name, specification);
        required.add(name);
        return this;
    }

    ToolSchema optional(String name, Map<String, Object> specification) {
        properties.put(name, specification);
        return this;
    }

    Map<String, Object> build() {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        // Not Map.copyOf: it returns the same order-randomising immutable map as Map.of, which
        // would undo everything the LinkedHashMap above is for.
        schema.put("properties", Collections.unmodifiableMap(new LinkedHashMap<>(properties)));
        if (!required.isEmpty()) {
            schema.put("required", List.copyOf(required));
        }
        // Closed by default: an argument the tool would silently ignore is better rejected by the
        // model's own schema validation than acted on as if it had taken effect.
        schema.put("additionalProperties", false);
        return schema;
    }

    static Map<String, Object> string(String description) {
        return Map.of("type", "string", "description", description);
    }

    static Map<String, Object> integer(String description, int minimum, int maximum) {
        Map<String, Object> specification = new LinkedHashMap<>();
        specification.put("type", "integer");
        specification.put("description", description);
        specification.put("minimum", minimum);
        specification.put("maximum", maximum);
        return specification;
    }

    static Map<String, Object> stringArray(String description) {
        Map<String, Object> specification = new LinkedHashMap<>();
        specification.put("type", "array");
        specification.put("description", description);
        specification.put("items", Map.of("type", "string"));
        return specification;
    }
}
