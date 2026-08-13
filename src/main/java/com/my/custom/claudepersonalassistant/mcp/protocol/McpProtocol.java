package com.my.custom.claudepersonalassistant.mcp.protocol;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;

/**
 * Wire constants of MCP revision {@code 2026-07-28}.
 *
 * <p>This revision is stateless by construction: there is no {@code initialize} exchange, no
 * {@code notifications/initialized}, no {@code Mcp-Session-Id} and no standalone SSE stream.
 * Everything a request needs travels with it, in {@code _meta} and in mirrored HTTP headers.
 */
public final class McpProtocol {

    public static final String VERSION = "2026-07-28";
    public static final List<String> SUPPORTED_VERSIONS = List.of(VERSION);

    public static final String JSONRPC_VERSION = "2.0";

    public static final String HEADER_PROTOCOL_VERSION = "MCP-Protocol-Version";
    public static final String HEADER_METHOD = "Mcp-Method";
    public static final String HEADER_NAME = "Mcp-Name";

    public static final String METHOD_TOOLS_LIST = "tools/list";
    public static final String METHOD_TOOLS_CALL = "tools/call";

    public static final String PARAM_NAME = "name";
    public static final String PARAM_ARGUMENTS = "arguments";
    public static final String PARAM_META = "_meta";

    public static final String META_PROTOCOL_VERSION = "io.modelcontextprotocol/protocolVersion";
    public static final String META_CLIENT_INFO = "io.modelcontextprotocol/clientInfo";
    public static final String META_CLIENT_CAPABILITIES = "io.modelcontextprotocol/clientCapabilities";

    /** Every result in this revision is tagged, so a client can tell a final answer from a retry. */
    public static final String RESULT_TYPE_COMPLETE = "complete";

    public static final int ERROR_INVALID_REQUEST = -32600;
    public static final int ERROR_METHOD_NOT_FOUND = -32601;
    public static final int ERROR_INVALID_PARAMS = -32602;
    public static final int ERROR_INTERNAL = -32603;
    /** Allocated by the specification for header values that disagree with the body. */
    public static final int ERROR_HEADER_MISMATCH = -32020;

    private static final String BASE64_PREFIX = "=?base64?";
    private static final String BASE64_SUFFIX = "?=";

    /**
     * Decodes the sentinel encoding clients must use for header values that cannot be carried as
     * plain ASCII. Values that are not encoded are returned untouched.
     */
    public static String decodeHeaderValue(String value) {
        if (value == null || !value.startsWith(BASE64_PREFIX) || !value.endsWith(BASE64_SUFFIX)
                || value.length() < BASE64_PREFIX.length() + BASE64_SUFFIX.length()) {
            return value;
        }
        String encoded = value.substring(BASE64_PREFIX.length(), value.length() - BASE64_SUFFIX.length());
        try {
            return new String(Base64.getDecoder().decode(encoded), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException malformed) {
            return value;
        }
    }

    private McpProtocol() {
    }
}
