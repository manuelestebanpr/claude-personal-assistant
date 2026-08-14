package com.my.custom.claudepersonalassistant.chat.dto;

import java.util.List;

/**
 * A tool as the chat UI needs it.
 *
 * <p>Deliberately not the MCP {@code ToolDescriptor}: the same translation discipline that keeps
 * Spring AI out of this module keeps the MCP wire model out of it too. The view gets a label, a
 * description and the fields it must collect — never a raw JSON Schema.
 *
 * @param serverId       server that offers it, sent back when running it
 * @param serverName     that server's label, so the list can be grouped
 * @param name           identifier passed back when the tool is invoked
 * @param title          human-readable label, falling back to the name when the server gave none
 * @param description    what the tool does
 * @param runnableAsIs   whether the tool takes no arguments and can be run straight from the picker
 * @param parameters     the arguments to collect first, empty when {@code runnableAsIs}
 */
public record ToolDto(String serverId, String serverName, String name, String title, String description,
        boolean runnableAsIs, List<ToolParameterDto> parameters) {
}
