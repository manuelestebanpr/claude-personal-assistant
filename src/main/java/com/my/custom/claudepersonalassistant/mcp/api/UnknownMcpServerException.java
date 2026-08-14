package com.my.custom.claudepersonalassistant.mcp.api;

import java.util.Collection;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/**
 * Raised when a caller addresses a server that is not configured.
 *
 * <p>A subtype rather than a plain {@link McpClientException} because the two mean opposite things
 * to a caller: a server that cannot be reached is the server's problem and worth retrying, while a
 * server that does not exist is the request's problem and never will be. Only the second is a 404 —
 * which is also why it carries the ids that do exist.
 */
@ResponseStatus(HttpStatus.NOT_FOUND)
public class UnknownMcpServerException extends McpClientException {

    public UnknownMcpServerException(String serverId, Collection<String> configured) {
        super("Unknown MCP server '%s'. Configured: %s".formatted(serverId, configured));
    }
}
