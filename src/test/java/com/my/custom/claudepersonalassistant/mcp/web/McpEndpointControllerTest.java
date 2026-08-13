package com.my.custom.claudepersonalassistant.mcp.web;

import java.util.Base64;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.my.custom.claudepersonalassistant.mcp.api.ToolResult;
import com.my.custom.claudepersonalassistant.mcp.domain.McpTool;
import com.my.custom.claudepersonalassistant.mcp.domain.ToolRegistry;
import com.my.custom.claudepersonalassistant.mcp.domain.UnknownToolException;
import com.my.custom.claudepersonalassistant.mcp.protocol.McpProtocol;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Pins the wire contract of MCP revision {@code 2026-07-28}.
 *
 * <p>Most of these cases exist because this revision deleted machinery earlier ones relied on:
 * there is no {@code initialize} to perform, no session to carry, and no GET stream to open, so
 * the first request a client sends is a useful one and anything session-shaped is refused.
 */
@WebMvcTest(controllers = McpEndpointController.class)
@Import(McpRequestValidator.class)
class McpEndpointControllerTest {

    private static final String TOOL = "get_current_hour";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ToolRegistry toolRegistry;

    @Test
    void listsToolsOnTheVeryFirstRequestWithNoHandshake() throws Exception {
        given(toolRegistry.tools()).willReturn(List.of(tool()));

        mockMvc.perform(post(McpEndpointController.MCP_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header(McpProtocol.HEADER_PROTOCOL_VERSION, McpProtocol.VERSION)
                        .header(McpProtocol.HEADER_METHOD, McpProtocol.METHOD_TOOLS_LIST)
                        .content(body(McpProtocol.METHOD_TOOLS_LIST, Map.of())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.jsonrpc").value("2.0"))
                .andExpect(jsonPath("$.id").value(1))
                .andExpect(jsonPath("$.result.resultType").value("complete"))
                .andExpect(jsonPath("$.result.tools[0].name").value(TOOL))
                .andExpect(jsonPath("$.result.tools[0].inputSchema.additionalProperties").value(false));
    }

    @Test
    void callsATool() throws Exception {
        given(toolRegistry.invoke(eq(TOOL), any())).willReturn(ToolResult.ok("The current time is 21:07 (Europe/Madrid)."));

        mockMvc.perform(callToolRequest(TOOL, TOOL))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.resultType").value("complete"))
                .andExpect(jsonPath("$.result.isError").value(false))
                .andExpect(jsonPath("$.result.content[0].type").value("text"))
                .andExpect(jsonPath("$.result.content[0].text").value("The current time is 21:07 (Europe/Madrid)."));
    }

    /** Execution failures must stay readable by the model, so they are results, not JSON-RPC errors. */
    @Test
    void reportsAToolFailureAsAnErrorResultRatherThanAProtocolError() throws Exception {
        given(toolRegistry.invoke(eq(TOOL), any())).willReturn(ToolResult.failed("clock unavailable"));

        mockMvc.perform(callToolRequest(TOOL, TOOL))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.isError").value(true))
                .andExpect(jsonPath("$.result.content[0].text").value("clock unavailable"))
                .andExpect(jsonPath("$.error").doesNotExist());
    }

    @Test
    void rejectsAnUnknownToolAsInvalidParams() throws Exception {
        willThrow(new UnknownToolException("nope")).given(toolRegistry).invoke(eq("nope"), any());

        mockMvc.perform(callToolRequest("nope", "nope"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.error.code").value(McpProtocol.ERROR_INVALID_PARAMS))
                .andExpect(jsonPath("$.result").doesNotExist());
    }

    @Test
    void rejectsAnUnknownMethodWithNotFound() throws Exception {
        mockMvc.perform(post(McpEndpointController.MCP_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header(McpProtocol.HEADER_PROTOCOL_VERSION, McpProtocol.VERSION)
                        .header(McpProtocol.HEADER_METHOD, "resources/list")
                        .content(body("resources/list", Map.of())))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value(McpProtocol.ERROR_METHOD_NOT_FOUND));
    }

    /**
     * The transport mirrors body fields into headers so intermediaries can route without parsing
     * the body; if the two disagree, a proxy and the server could act on different values.
     */
    @Test
    void rejectsAMethodHeaderThatContradictsTheBody() throws Exception {
        mockMvc.perform(post(McpEndpointController.MCP_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header(McpProtocol.HEADER_PROTOCOL_VERSION, McpProtocol.VERSION)
                        .header(McpProtocol.HEADER_METHOD, McpProtocol.METHOD_TOOLS_LIST)
                        .content(body(McpProtocol.METHOD_TOOLS_CALL, Map.of("name", TOOL))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value(McpProtocol.ERROR_HEADER_MISMATCH));
    }

    @Test
    void rejectsANameHeaderThatContradictsTheBody() throws Exception {
        mockMvc.perform(callToolRequest(TOOL, "something_else"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value(McpProtocol.ERROR_HEADER_MISMATCH));
    }

    @Test
    void acceptsABase64EncodedNameHeader() throws Exception {
        given(toolRegistry.invoke(eq(TOOL), any())).willReturn(ToolResult.ok("ok"));
        String encoded = "=?base64?" + Base64.getEncoder().encodeToString(TOOL.getBytes()) + "?=";

        mockMvc.perform(callToolRequest(TOOL, encoded))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.isError").value(false));
    }

    @Test
    void rejectsARequestWithoutTheProtocolVersionHeader() throws Exception {
        mockMvc.perform(post(McpEndpointController.MCP_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header(McpProtocol.HEADER_METHOD, McpProtocol.METHOD_TOOLS_LIST)
                        .content(body(McpProtocol.METHOD_TOOLS_LIST, Map.of())))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value(McpProtocol.ERROR_HEADER_MISMATCH));
    }

    @Test
    void tellsAClientWhichProtocolVersionsItSupports() throws Exception {
        mockMvc.perform(post(McpEndpointController.MCP_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header(McpProtocol.HEADER_PROTOCOL_VERSION, "2025-11-25")
                        .header(McpProtocol.HEADER_METHOD, McpProtocol.METHOD_TOOLS_LIST)
                        .content(body(McpProtocol.METHOD_TOOLS_LIST, Map.of(), "2025-11-25")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.data.requested").value("2025-11-25"))
                .andExpect(jsonPath("$.error.data.supported[0]").value(McpProtocol.VERSION));
    }

    /** GET and DELETE belonged to the session transport this revision removed. */
    @Test
    void refusesTheSessionBasedTransportOfEarlierRevisions() throws Exception {
        mockMvc.perform(get(McpEndpointController.MCP_PATH)).andExpect(status().isMethodNotAllowed());
        mockMvc.perform(delete(McpEndpointController.MCP_PATH)).andExpect(status().isMethodNotAllowed());
    }

    @Test
    void ignoresASessionIdFromAnOlderClientAndNeverEchoesIt() throws Exception {
        given(toolRegistry.tools()).willReturn(List.of(tool()));

        mockMvc.perform(post(McpEndpointController.MCP_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header(McpProtocol.HEADER_PROTOCOL_VERSION, McpProtocol.VERSION)
                        .header(McpProtocol.HEADER_METHOD, McpProtocol.METHOD_TOOLS_LIST)
                        .header("Mcp-Session-Id", "abc123")
                        .header("Last-Event-ID", "42")
                        .content(body(McpProtocol.METHOD_TOOLS_LIST, Map.of())))
                .andExpect(status().isOk())
                .andExpect(header().doesNotExist("Mcp-Session-Id"));
    }

    private org.springframework.test.web.servlet.RequestBuilder callToolRequest(String bodyName, String headerName) {
        return post(McpEndpointController.MCP_PATH)
                .contentType(MediaType.APPLICATION_JSON)
                .header(McpProtocol.HEADER_PROTOCOL_VERSION, McpProtocol.VERSION)
                .header(McpProtocol.HEADER_METHOD, McpProtocol.METHOD_TOOLS_CALL)
                .header(McpProtocol.HEADER_NAME, headerName)
                .content(body(McpProtocol.METHOD_TOOLS_CALL,
                        Map.of("name", bodyName, "arguments", Map.of())));
    }

    private String body(String method, Map<String, Object> params) {
        return body(method, params, McpProtocol.VERSION);
    }

    private String body(String method, Map<String, Object> params, String metaVersion) {
        StringBuilder json = new StringBuilder("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"")
                .append(method).append("\",\"params\":{");
        params.forEach((key, value) -> json.append('"').append(key).append("\":")
                .append(value instanceof Map<?, ?> ? "{}" : "\"" + value + "\"").append(','));
        json.append("\"_meta\":{\"").append(McpProtocol.META_PROTOCOL_VERSION).append("\":\"")
                .append(metaVersion).append("\",\"").append(McpProtocol.META_CLIENT_INFO)
                .append("\":{\"name\":\"test\",\"version\":\"1.0\"},\"")
                .append(McpProtocol.META_CLIENT_CAPABILITIES).append("\":{}}}}");
        return json.toString();
    }

    private McpTool tool() {
        return new McpTool() {
            @Override
            public String name() {
                return TOOL;
            }

            @Override
            public String title() {
                return "Current hour";
            }

            @Override
            public String description() {
                return "Returns the current time of day on the server, with its time zone.";
            }

            @Override
            public Map<String, Object> inputSchema() {
                return Map.of("type", "object", "additionalProperties", false);
            }

            @Override
            public String execute(Map<String, Object> arguments) {
                return "21:07";
            }
        };
    }
}
