package com.my.custom.claudepersonalassistant.chat.dto;

/**
 * One field the tool palette has to render before it can run a tool.
 *
 * <p>Translated from the MCP {@code ToolParameter} for the same reason {@link ToolDto} is
 * translated from {@code ToolDescriptor}: the view needs a label, a hint and whether it may be left
 * blank, not the module's wire model.
 *
 * @param name        key sent back in the arguments object
 * @param description hint shown under the field
 * @param type        {@code string}, {@code integer}, {@code number}, {@code boolean} or
 *                    {@code array} — decides which control the palette draws
 * @param required    whether the palette may submit without it
 */
public record ToolParameterDto(String name, String description, String type, boolean required) {
}
