package com.my.custom.claudepersonalassistant.mcp.client;

import java.util.Map;

import com.fasterxml.jackson.annotation.JsonInclude;

import com.my.custom.claudepersonalassistant.mcp.protocol.McpProtocol;

/**
 * The outbound JSON-RPC request envelope both wire clients put on the wire — the client-side
 * counterpart of the server's inbound {@code mcp/protocol/JsonRpcRequest}, kept separate because
 * the two sides evolve independently.
 *
 * <p>A record rather than a hand-built map for two reasons: the envelope is written down once
 * instead of once per client, and Jackson serializes record components in declaration order, so
 * the request bytes are deterministic — {@code Map.of} randomises its iteration order per JVM
 * start, which would reorder an otherwise unchanged request between restarts.
 *
 * <p>{@code NON_NULL} is what makes {@link #notification} correct: a notification carries no
 * {@code id} and no {@code params}, and one that puts {@code "id": null} on the wire is not a
 * notification but a malformed request a server may reject.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
record JsonRpcRequestBody(String jsonrpc, Long id, String method, Map<String, Object> params) {

    static JsonRpcRequestBody of(long id, String method, Map<String, Object> params) {
        return new JsonRpcRequestBody(McpProtocol.JSONRPC_VERSION, id, method, params);
    }

    static JsonRpcRequestBody notification(String method) {
        return new JsonRpcRequestBody(McpProtocol.JSONRPC_VERSION, null, method, null);
    }
}
