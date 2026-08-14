package com.my.custom.claudepersonalassistant.assistant.dto;

import java.util.Map;

/**
 * A tool the model may call during a turn: its name, what it does, and the JSON Schema of the
 * arguments it accepts.
 */
public record ToolSpecification(String name, String description, Map<String, Object> inputSchema) {
}
