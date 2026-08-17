package com.my.custom.claudepersonalassistant.mcp.domain.tool;

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
 *
 * <p>Lives in {@code domain.tool} rather than beside one tool group because it is the shape every
 * group's schema is built from. It was package-private in {@code domain.tool.google} until a second
 * group needed it, which is the only reason it moved.
 */
public final class ToolSchema {

    private final Map<String, Object> properties = new LinkedHashMap<>();
    private final List<String> required = new ArrayList<>();

    private ToolSchema() {
    }

    public static ToolSchema object() {
        return new ToolSchema();
    }

    public ToolSchema required(String name, Map<String, Object> specification) {
        properties.put(name, specification);
        required.add(name);
        return this;
    }

    public ToolSchema optional(String name, Map<String, Object> specification) {
        properties.put(name, specification);
        return this;
    }

    public Map<String, Object> build() {
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

    public static Map<String, Object> string(String description) {
        return Map.of("type", "string", "description", description);
    }

    public static Map<String, Object> integer(String description, int minimum, int maximum) {
        Map<String, Object> specification = new LinkedHashMap<>();
        specification.put("type", "integer");
        specification.put("description", description);
        specification.put("minimum", minimum);
        specification.put("maximum", maximum);
        return specification;
    }

    /**
     * A decimal quantity with a floor but no ceiling — money and weights.
     *
     * <p>{@code number}, not {@code integer}: a receipt sells 1.085 kg of tomatoes, and declaring
     * the field as an integer makes the model round before the value ever reaches us.
     */
    public static Map<String, Object> number(String description, double minimum) {
        Map<String, Object> specification = new LinkedHashMap<>();
        specification.put("type", "number");
        specification.put("description", description);
        specification.put("minimum", minimum);
        return specification;
    }

    public static Map<String, Object> stringArray(String description) {
        return arrayOf(description, Map.of("type", "string"));
    }

    public static Map<String, Object> integerArray(String description) {
        return arrayOf(description, Map.of("type", "integer"));
    }

    /**
     * An array whose items are themselves objects, described by a nested {@link ToolSchema}.
     *
     * <p>Nesting the item schema rather than flattening it into parallel arrays is what lets one
     * call carry a whole shopping list: the model fills a list of complete rows instead of four
     * same-length arrays it has to keep aligned.
     */
    public static Map<String, Object> objectArray(String description, ToolSchema item) {
        return arrayOf(description, item.build());
    }

    private static Map<String, Object> arrayOf(String description, Map<String, Object> items) {
        Map<String, Object> specification = new LinkedHashMap<>();
        specification.put("type", "array");
        specification.put("description", description);
        specification.put("items", items);
        return specification;
    }
}
