package com.my.custom.claudepersonalassistant.assistant.client;

import java.util.Map;

import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import com.my.custom.claudepersonalassistant.assistant.api.ToolExecutor;
import com.my.custom.claudepersonalassistant.assistant.dto.ToolExecutionResult;
import com.my.custom.claudepersonalassistant.assistant.dto.ToolSpecification;

/**
 * Adapts one {@link ToolSpecification} to Spring AI's raw, String-based {@link ToolCallback} SPI,
 * translating its JSON arguments/result into and out of the module's own {@link ToolExecutor}
 * port.
 */
class ToolCallbackAdapter implements ToolCallback {

    private static final TypeReference<Map<String, Object>> ARGUMENTS_TYPE = new TypeReference<>() { };

    private final ToolSpecification specification;
    private final ToolExecutor toolExecutor;
    private final ObjectMapper objectMapper;

    ToolCallbackAdapter(ToolSpecification specification, ToolExecutor toolExecutor, ObjectMapper objectMapper) {
        this.specification = specification;
        this.toolExecutor = toolExecutor;
        this.objectMapper = objectMapper;
    }

    @Override
    public ToolDefinition getToolDefinition() {
        return ToolDefinition.builder()
                .name(specification.name())
                .description(specification.description())
                .inputSchema(objectMapper.writeValueAsString(specification.inputSchema()))
                .build();
    }

    @Override
    public String call(String toolInput) {
        Map<String, Object> arguments = objectMapper.readValue(toolInput, ARGUMENTS_TYPE);
        ToolExecutionResult result = toolExecutor.execute(specification.name(), arguments);
        return result.error() ? "Error: " + result.text() : result.text();
    }
}
