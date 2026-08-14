package com.my.custom.claudepersonalassistant.mcp.client;

import java.util.Map;

import com.my.custom.claudepersonalassistant.mcp.protocol.JsonRpcResponse;

/**
 * Puts one JSON-RPC request on the wire and hands back what came off it.
 *
 * <p>The seam between the two MCP revision families. Everything above this interface — listing
 * tools, calling one, reading the result — is identical across revisions; what differs is whether a
 * session has to be opened first, which is exactly what an implementation hides.
 */
interface McpWireClient {

    /**
     * @param method   JSON-RPC method name
     * @param toolName the tool being addressed, or {@code null} for methods that address none
     * @param params   the method's parameters, before any revision-specific decoration
     */
    JsonRpcResponse send(String method, String toolName, Map<String, Object> params);
}
