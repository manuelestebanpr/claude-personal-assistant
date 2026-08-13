package com.my.custom.claudepersonalassistant.mcp.protocol;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * The error member of a JSON-RPC response.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record JsonRpcError(int code, String message, Object data) {

    public static JsonRpcError of(int code, String message) {
        return new JsonRpcError(code, message, null);
    }
}
