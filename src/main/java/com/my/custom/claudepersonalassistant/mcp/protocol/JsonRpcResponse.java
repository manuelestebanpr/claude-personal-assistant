package com.my.custom.claudepersonalassistant.mcp.protocol;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * A JSON-RPC response. Exactly one of {@code result} and {@code error} is present, which is why
 * nulls are dropped rather than serialized.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record JsonRpcResponse(String jsonrpc, Object id, Object result, JsonRpcError error) {

    public static JsonRpcResponse success(Object id, Object result) {
        return new JsonRpcResponse(McpProtocol.JSONRPC_VERSION, id, result, null);
    }

    public static JsonRpcResponse failure(Object id, JsonRpcError error) {
        return new JsonRpcResponse(McpProtocol.JSONRPC_VERSION, id, null, error);
    }
}
