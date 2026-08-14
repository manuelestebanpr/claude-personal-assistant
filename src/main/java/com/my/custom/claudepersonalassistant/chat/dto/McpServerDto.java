package com.my.custom.claudepersonalassistant.chat.dto;

/**
 * An MCP server as the picker needs it.
 *
 * <p>Unreachable servers are listed too — with {@code reachable} false and the reason in
 * {@code detail} — because a server that is configured but down is something the user has to be
 * able to see, not a row that quietly disappears.
 *
 * @param id        identifier sent back when running one of its tools
 * @param name      human-readable label
 * @param url       endpoint it is reached at
 * @param protocol  MCP revision family the client uses for it
 * @param reachable whether it answered the last catalogue request
 * @param toolCount how many tools it exposed
 * @param detail    why it is unreachable, or {@code null}
 */
public record McpServerDto(String id, String name, String url, String protocol,
        boolean reachable, int toolCount, String detail) {
}
