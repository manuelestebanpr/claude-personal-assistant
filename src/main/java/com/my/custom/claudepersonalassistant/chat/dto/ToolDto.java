package com.my.custom.claudepersonalassistant.chat.dto;

/**
 * A tool as the chat UI needs it.
 *
 * <p>Deliberately not the MCP {@code ToolDescriptor}: the same translation discipline that keeps
 * Spring AI out of this module keeps the MCP wire model out of it too. The view needs a label, a
 * description and whether it can be run with one click — not a JSON Schema.
 *
 * @param name           identifier passed back when the tool is invoked
 * @param title          human-readable label, falling back to the name when the server gave none
 * @param description    what the tool does
 * @param runnableAsIs   whether the tool takes no arguments and can be run straight from the palette
 */
public record ToolDto(String name, String title, String description, boolean runnableAsIs) {
}
