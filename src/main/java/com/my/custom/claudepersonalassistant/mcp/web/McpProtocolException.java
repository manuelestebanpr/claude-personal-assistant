package com.my.custom.claudepersonalassistant.mcp.web;

import org.springframework.http.HttpStatus;

import com.my.custom.claudepersonalassistant.mcp.protocol.JsonRpcError;

/**
 * A request that fails the protocol's own rules — a missing or contradictory header, an
 * unsupported revision, an unknown method — carrying the HTTP status and JSON-RPC error the
 * specification prescribes for it.
 */
class McpProtocolException extends RuntimeException {

    private final transient HttpStatus status;
    private final transient JsonRpcError error;

    McpProtocolException(HttpStatus status, JsonRpcError error) {
        super(error.message());
        this.status = status;
        this.error = error;
    }

    HttpStatus status() {
        return status;
    }

    JsonRpcError error() {
        return error;
    }
}
