package com.my.custom.claudepersonalassistant.mcp.protocol;

import java.util.Map;

/**
 * An inbound JSON-RPC message. The id is absent on notifications and may be a string or a number,
 * so it is carried untyped and echoed back as received.
 */
public record JsonRpcRequest(String jsonrpc, Object id, String method, Map<String, Object> params) {

    public Map<String, Object> paramsOrEmpty() {
        return params == null ? Map.of() : params;
    }

    /** The {@code _meta} block every request in this revision must carry. */
    public Map<String, Object> meta() {
        return paramsOrEmpty().get(McpProtocol.PARAM_META) instanceof Map<?, ?> meta
                ? castToStringKeyedMap(meta)
                : Map.of();
    }

    public String metaProtocolVersion() {
        return meta().get(McpProtocol.META_PROTOCOL_VERSION) instanceof String version ? version : null;
    }

    public String toolName() {
        return paramsOrEmpty().get(McpProtocol.PARAM_NAME) instanceof String name ? name : null;
    }

    public Map<String, Object> toolArguments() {
        return paramsOrEmpty().get(McpProtocol.PARAM_ARGUMENTS) instanceof Map<?, ?> arguments
                ? castToStringKeyedMap(arguments)
                : Map.of();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> castToStringKeyedMap(Map<?, ?> map) {
        return (Map<String, Object>) map;
    }
}
