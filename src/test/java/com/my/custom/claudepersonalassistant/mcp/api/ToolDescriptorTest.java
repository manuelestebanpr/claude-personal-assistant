package com.my.custom.claudepersonalassistant.mcp.api;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Reading the arguments back out of the schema is what lets a caller ask a human for them, so a
 * misread here is the difference between a usable tool and one that can only be guessed at.
 */
class ToolDescriptorTest {

    @Test
    void readsEachArgumentOffTheSchemaInTheOrderItWasDeclared() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("query", Map.of("type", "string", "description", "Gmail query."));
        properties.put("max_results", Map.of("type", "integer", "description", "How many."));

        ToolDescriptor descriptor = descriptor(Map.of(
                "type", "object",
                "properties", properties,
                "required", List.of("query"),
                "additionalProperties", false));

        assertThat(descriptor.parameters()).containsExactly(
                new ToolParameter("query", "Gmail query.", "string", true),
                new ToolParameter("max_results", "How many.", "integer", false));
        assertThat(descriptor.takesNoArguments()).isFalse();
    }

    /** The specification's recommended empty form — what a no-argument tool advertises. */
    @Test
    void treatsTheEmptyObjectSchemaAsTakingNoArguments() {
        ToolDescriptor descriptor = descriptor(Map.of("type", "object", "additionalProperties", false));

        assertThat(descriptor.parameters()).isEmpty();
        assertThat(descriptor.takesNoArguments()).isTrue();
    }

    @Test
    void survivesAServerThatSendsNoSchemaAtAll() {
        assertThat(descriptor(null).parameters()).isEmpty();
        assertThat(descriptor(null).takesNoArguments()).isTrue();
    }

    /** A property with no {@code type} still has to render as something. */
    @Test
    void fallsBackToStringForAnUntypedProperty() {
        ToolDescriptor descriptor = descriptor(Map.of(
                "type", "object", "properties", Map.of("note", Map.of())));

        assertThat(descriptor.parameters())
                .containsExactly(new ToolParameter("note", "", "string", false));
    }

    private ToolDescriptor descriptor(Map<String, Object> inputSchema) {
        return new ToolDescriptor("local", "Local","a_tool", "A tool", "Does a thing.", inputSchema);
    }
}
