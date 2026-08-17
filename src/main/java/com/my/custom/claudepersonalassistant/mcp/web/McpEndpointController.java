package com.my.custom.claudepersonalassistant.mcp.web;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

import com.my.custom.claudepersonalassistant.mcp.api.ToolResult;
import com.my.custom.claudepersonalassistant.mcp.domain.McpTool;
import com.my.custom.claudepersonalassistant.mcp.domain.ToolRegistry;
import com.my.custom.claudepersonalassistant.mcp.domain.UnknownToolException;
import com.my.custom.claudepersonalassistant.mcp.protocol.CallToolResult;
import com.my.custom.claudepersonalassistant.mcp.protocol.JsonRpcError;
import com.my.custom.claudepersonalassistant.mcp.protocol.JsonRpcRequest;
import com.my.custom.claudepersonalassistant.mcp.protocol.JsonRpcResponse;
import com.my.custom.claudepersonalassistant.mcp.protocol.ListToolsResult;
import com.my.custom.claudepersonalassistant.mcp.protocol.McpProtocol;
import com.my.custom.claudepersonalassistant.mcp.protocol.ToolDefinition;

/**
 * The single MCP endpoint.
 *
 * <p>Revision {@code 2026-07-28} collapsed the transport to one POST per message: no session to
 * establish, no handshake to complete, no stream to hold open. A client may call {@code tools/list}
 * as its very first request.
 */
@RestController
class McpEndpointController {

    static final String MCP_PATH = "/mcp";

    /**
     * The protocol boundary is synchronous, while {@code ToolInvokedEvent} only reaches its listener
     * after the transaction commits — so without this line a request that was served has no trace of
     * its own, and a missing tool metric cannot be told apart from a request that never arrived.
     */
    static final String HANDLED_MESSAGE = "Handled MCP request";

    static final String KEY_METHOD = "method";
    static final String KEY_TOOL = "tool";
    static final String KEY_CODE = "code";

    private static final Logger log = LoggerFactory.getLogger(McpEndpointController.class);

    private final ToolRegistry toolRegistry;
    private final McpRequestValidator validator;

    McpEndpointController(ToolRegistry toolRegistry, McpRequestValidator validator) {
        this.toolRegistry = toolRegistry;
        this.validator = validator;
    }

    @PostMapping(path = MCP_PATH,
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<JsonRpcResponse> handle(@RequestBody JsonRpcRequest request,
            @RequestHeader HttpHeaders headers) {
        try {
            validator.validate(request, headers);
            JsonRpcResponse response = dispatch(request);
            log.atDebug()
                    .addKeyValue(KEY_METHOD, request.method())
                    // Null on tools/list, which carries no tool — the absence is the fact.
                    .addKeyValue(KEY_TOOL, request.toolName())
                    .log(HANDLED_MESSAGE);
            return ResponseEntity.ok(response);
        } catch (McpProtocolException rejected) {
            log.atWarn()
                    .addKeyValue(KEY_METHOD, request.method())
                    .addKeyValue(KEY_CODE, rejected.error().code())
                    .log("Rejected MCP request: {}", rejected.getMessage());
            return ResponseEntity.status(rejected.status())
                    .body(JsonRpcResponse.failure(request.id(), rejected.error()));
        }
    }

    /**
     * GET and DELETE belonged to the session-based transport of revisions 2025-03-26 through
     * 2025-11-25. Answering 405 is how this revision tells an older client it does not speak that
     * shape, without ever minting a session id.
     */
    @RequestMapping(path = MCP_PATH, method = { RequestMethod.GET, RequestMethod.DELETE })
    ResponseEntity<Void> rejectSessionTransport() {
        return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED).build();
    }

    private JsonRpcResponse dispatch(JsonRpcRequest request) {
        return switch (request.method()) {
            case McpProtocol.METHOD_TOOLS_LIST -> JsonRpcResponse.success(request.id(), listTools());
            case McpProtocol.METHOD_TOOLS_CALL -> JsonRpcResponse.success(request.id(), callTool(request));
            default -> throw new McpProtocolException(HttpStatus.NOT_FOUND,
                    JsonRpcError.of(McpProtocol.ERROR_METHOD_NOT_FOUND, "Method not found: " + request.method()));
        };
    }

    private ListToolsResult listTools() {
        List<ToolDefinition> definitions = toolRegistry.tools().stream()
                .map(this::toDefinition)
                .toList();
        return ListToolsResult.of(definitions);
    }

    private Object callTool(JsonRpcRequest request) {
        try {
            ToolResult result = toolRegistry.invoke(request.toolName(), request.toolArguments());
            return CallToolResult.of(result.text(), result.error());
        } catch (UnknownToolException unknown) {
            // A name the server does not know is a malformed request, not something the model can
            // fix by retrying with different arguments, so it is a protocol error.
            throw new McpProtocolException(HttpStatus.OK,
                    JsonRpcError.of(McpProtocol.ERROR_INVALID_PARAMS, unknown.getMessage()));
        }
    }

    private ToolDefinition toDefinition(McpTool tool) {
        return new ToolDefinition(tool.name(), tool.title(), tool.description(), tool.inputSchema());
    }
}
