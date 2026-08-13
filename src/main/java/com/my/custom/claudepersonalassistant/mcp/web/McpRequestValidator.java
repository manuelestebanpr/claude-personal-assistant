package com.my.custom.claudepersonalassistant.mcp.web;

import java.util.Objects;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import com.my.custom.claudepersonalassistant.mcp.protocol.JsonRpcError;
import com.my.custom.claudepersonalassistant.mcp.protocol.JsonRpcRequest;
import com.my.custom.claudepersonalassistant.mcp.protocol.McpProtocol;
import com.my.custom.claudepersonalassistant.mcp.protocol.UnsupportedProtocolVersion;

/**
 * Enforces the transport rules of MCP {@code 2026-07-28}.
 *
 * <p>The transport mirrors selected body fields into headers so proxies can route without parsing
 * the body. That only stays safe if both sides agree, so the specification requires the server to
 * reject any request where a header and the body disagree — otherwise a load balancer could route
 * on one value while the server executes another.
 */
@Component
class McpRequestValidator {

    void validate(JsonRpcRequest request, HttpHeaders headers) {
        requireJsonRpcEnvelope(request);
        requireSupportedVersion(request, headers);
        requireMatchingMethodHeader(request, headers);
        requireMatchingNameHeader(request, headers);
    }

    private void requireJsonRpcEnvelope(JsonRpcRequest request) {
        if (!McpProtocol.JSONRPC_VERSION.equals(request.jsonrpc()) || !StringUtils.hasText(request.method())) {
            throw new McpProtocolException(HttpStatus.BAD_REQUEST,
                    JsonRpcError.of(McpProtocol.ERROR_INVALID_REQUEST, "Not a JSON-RPC 2.0 request"));
        }
    }

    private void requireSupportedVersion(JsonRpcRequest request, HttpHeaders headers) {
        String header = headers.getFirst(McpProtocol.HEADER_PROTOCOL_VERSION);
        if (!StringUtils.hasText(header)) {
            // A server that does not serve pre-2025-06-18 clients must reject a request with no
            // version header rather than guessing an era for it.
            throw headerMismatch("Missing required header " + McpProtocol.HEADER_PROTOCOL_VERSION);
        }
        if (!McpProtocol.SUPPORTED_VERSIONS.contains(header)) {
            throw new McpProtocolException(HttpStatus.BAD_REQUEST,
                    new JsonRpcError(McpProtocol.ERROR_INVALID_REQUEST,
                            "Unsupported protocol version: " + header,
                            new UnsupportedProtocolVersion(header, McpProtocol.SUPPORTED_VERSIONS)));
        }
        String body = request.metaProtocolVersion();
        if (!Objects.equals(header, body)) {
            throw headerMismatch("%s header value '%s' does not match body value '%s'"
                    .formatted(McpProtocol.HEADER_PROTOCOL_VERSION, header, body));
        }
    }

    private void requireMatchingMethodHeader(JsonRpcRequest request, HttpHeaders headers) {
        String header = headers.getFirst(McpProtocol.HEADER_METHOD);
        if (!StringUtils.hasText(header)) {
            throw headerMismatch("Missing required header " + McpProtocol.HEADER_METHOD);
        }
        if (!header.equals(request.method())) {
            throw headerMismatch("%s header value '%s' does not match body value '%s'"
                    .formatted(McpProtocol.HEADER_METHOD, header, request.method()));
        }
    }

    private void requireMatchingNameHeader(JsonRpcRequest request, HttpHeaders headers) {
        if (!McpProtocol.METHOD_TOOLS_CALL.equals(request.method())) {
            return;
        }
        String header = headers.getFirst(McpProtocol.HEADER_NAME);
        if (!StringUtils.hasText(header)) {
            throw headerMismatch("Missing required header " + McpProtocol.HEADER_NAME);
        }
        // Names outside the header-safe ASCII range arrive base64-wrapped and must be decoded
        // before they can be compared with the body.
        String decoded = McpProtocol.decodeHeaderValue(header);
        if (!decoded.equals(request.toolName())) {
            throw headerMismatch("%s header value '%s' does not match body value '%s'"
                    .formatted(McpProtocol.HEADER_NAME, decoded, request.toolName()));
        }
    }

    private McpProtocolException headerMismatch(String message) {
        return new McpProtocolException(HttpStatus.BAD_REQUEST,
                JsonRpcError.of(McpProtocol.ERROR_HEADER_MISMATCH, "Header mismatch: " + message));
    }
}
