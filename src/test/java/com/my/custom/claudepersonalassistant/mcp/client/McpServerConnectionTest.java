package com.my.custom.claudepersonalassistant.mcp.client;

import java.util.List;
import java.util.Map;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.slf4j.event.KeyValuePair;
import tools.jackson.databind.json.JsonMapper;

import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import com.my.custom.claudepersonalassistant.mcp.api.McpClientException;
import com.my.custom.claudepersonalassistant.mcp.api.McpServerDescriptor;
import com.my.custom.claudepersonalassistant.mcp.api.ToolDescriptor;
import com.my.custom.claudepersonalassistant.mcp.api.ToolResult;
import com.my.custom.claudepersonalassistant.mcp.config.McpProperties;
import com.my.custom.claudepersonalassistant.mcp.domain.ToolExecutionException;
import com.my.custom.claudepersonalassistant.mcp.protocol.McpProtocol;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.mock;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * Pins what a connection to a {@code STATELESS} server actually puts on the wire.
 *
 * <p>The point of revision {@code 2026-07-28} is that a client has nothing to set up: these tests
 * assert that discovering and calling a tool each cost exactly one request, with no
 * {@code initialize} and no {@code notifications/initialized} in front of them.
 */
class McpServerConnectionTest {

    private static final String URL = "http://localhost:8080/mcp";

    private MockRestServiceServer server;
    private McpServerConnection connection;

    private final Logger logger = (Logger) LoggerFactory.getLogger(McpServerConnection.class);
    private ListAppender<ILoggingEvent> appender;
    private Level previousLevel;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        McpProperties.Server configured = new McpProperties.Server("local", "Local", "http://localhost:8080",
                "/mcp", McpProperties.Protocol.STATELESS, null, false);
        connection = new McpServerConnection(configured,
                new StatelessWireClient(builder.build(), URL), JsonMapper.builder().build());
    }

    @BeforeEach
    void attachAppender() {
        appender = new ListAppender<>();
        appender.start();
        previousLevel = logger.getLevel();
        logger.setLevel(Level.TRACE);
        logger.addAppender(appender);
    }

    @AfterEach
    void detachAppender() {
        logger.detachAppender(appender);
        logger.setLevel(previousLevel);
    }

    private List<ILoggingEvent> eventsWithMessage(String message) {
        return appender.list.stream().filter(event -> message.equals(event.getMessage())).toList();
    }

    private Object keyValue(ILoggingEvent event, String key) {
        for (KeyValuePair pair : event.getKeyValuePairs()) {
            if (key.equals(pair.key)) {
                return pair.value;
            }
        }
        return null;
    }

    @Test
    void discoversToolsInASingleRequestWithNoHandshake() {
        server.expect(requestTo(URL))
                .andExpect(method(org.springframework.http.HttpMethod.POST))
                .andExpect(header(McpProtocol.HEADER_PROTOCOL_VERSION, McpProtocol.VERSION))
                .andExpect(header(McpProtocol.HEADER_METHOD, McpProtocol.METHOD_TOOLS_LIST))
                .andExpect(header("Accept", "application/json, text/event-stream"))
                .andExpect(jsonPath("$.params._meta['" + McpProtocol.META_PROTOCOL_VERSION + "']")
                        .value(McpProtocol.VERSION))
                .andRespond(withSuccess("""
                        {"jsonrpc":"2.0","id":1,"result":{"resultType":"complete","tools":[
                          {"name":"get_current_hour","title":"Current hour","description":"Returns the time.",
                           "inputSchema":{"type":"object","additionalProperties":false}}]}}
                        """, MediaType.APPLICATION_JSON));

        List<ToolDescriptor> tools = connection.listTools();

        assertThat(tools).singleElement().satisfies(tool -> {
            assertThat(tool.name()).isEqualTo("get_current_hour");
            // Every tool remembers where it came from, or a call could not be routed back.
            assertThat(tool.serverId()).isEqualTo("local");
            assertThat(tool.serverName()).isEqualTo("Local");
            assertThat(tool.takesNoArguments()).isTrue();
        });
        // verify() fails if any extra request was made, which is the assertion that no
        // initialize/initialized exchange happened.
        server.verify();
    }

    @Test
    void callsAToolMirroringItsNameIntoTheHeader() {
        server.expect(requestTo(URL))
                .andExpect(header(McpProtocol.HEADER_METHOD, McpProtocol.METHOD_TOOLS_CALL))
                .andExpect(header(McpProtocol.HEADER_NAME, "get_current_hour"))
                .andExpect(jsonPath("$.params.name").value("get_current_hour"))
                .andExpect(jsonPath("$.params.arguments.zone").value("UTC"))
                .andRespond(withSuccess("""
                        {"jsonrpc":"2.0","id":1,"result":{"resultType":"complete",
                         "content":[{"type":"text","text":"21:07 (UTC)."}],"isError":false}}
                        """, MediaType.APPLICATION_JSON));

        ToolResult result = connection.callTool("get_current_hour", Map.of("zone", "UTC"));

        assertThat(result).isEqualTo(ToolResult.ok("21:07 (UTC)."));
        server.verify();
    }

    @Test
    void surfacesAToolFailureAsAnErrorResultNotAnException() {
        server.expect(requestTo(URL)).andRespond(withSuccess("""
                {"jsonrpc":"2.0","id":1,"result":{"resultType":"complete",
                 "content":[{"type":"text","text":"clock unavailable"}],"isError":true}}
                """, MediaType.APPLICATION_JSON));

        assertThat(connection.callTool("get_current_hour", Map.of()))
                .isEqualTo(ToolResult.failed("clock unavailable"));
    }

    @Test
    void raisesAProtocolErrorAsAClientExceptionNamingTheServer() {
        server.expect(requestTo(URL)).andRespond(withSuccess("""
                {"jsonrpc":"2.0","id":1,"error":{"code":-32602,"message":"Unknown tool: nope"}}
                """, MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> connection.callTool("nope", Map.of()))
                .isInstanceOf(McpClientException.class)
                .hasMessageContaining("local")
                .hasMessageContaining("-32602");
    }

    /** A server that is down is a fact to report, not an exception for the picker to handle. */
    @Test
    void describesAnUnreachableServerInsteadOfThrowing() {
        server.expect(requestTo(URL)).andRespond(withServerError());

        McpServerDescriptor described = connection.describe();

        assertThat(described.id()).isEqualTo("local");
        assertThat(described.local()).isTrue();
        assertThat(described.reachable()).isFalse();
        assertThat(described.toolCount()).isZero();
        assertThat(described.detail()).isNotBlank();
    }

    /** A remote server's {@code baseUrl} host is what {@code local} is computed from. */
    @Test
    void describesAnUnreachableRemoteServerAsNotLocal() {
        String remoteUrl = "https://weather.example.com/mcp";
        RestClient.Builder remoteBuilder = RestClient.builder();
        MockRestServiceServer remoteServer = MockRestServiceServer.bindTo(remoteBuilder).build();
        remoteServer.expect(requestTo(remoteUrl)).andRespond(withServerError());
        McpProperties.Server remote = new McpProperties.Server("weather", "Weather",
                "https://weather.example.com", "/mcp", McpProperties.Protocol.STATELESS, null, false);
        McpServerConnection remoteConnection = new McpServerConnection(remote,
                new StatelessWireClient(remoteBuilder.build(), remoteUrl), JsonMapper.builder().build());

        McpServerDescriptor described = remoteConnection.describe();

        assertThat(described.local()).isFalse();
        assertThat(described.reachable()).isFalse();
        remoteServer.verify();
    }

    /**
     * The signal an auth/network/protocol-mismatch problem shows up as (e.g. a rejected Google
     * OAuth token, or a wrong SESSION-vs-STATELESS guess) — logged at WARN with the error detail so
     * it does not require reproducing the failure to see why.
     */
    @Test
    void logsAnUnreachableServerAtWarnWithTheErrorDetail() {
        server.expect(requestTo(URL)).andRespond(withServerError());

        connection.describe();

        List<ILoggingEvent> events = eventsWithMessage(McpServerConnection.UNREACHABLE_MESSAGE);
        assertThat(events).hasSize(1);
        ILoggingEvent event = events.getFirst();
        assertThat(event.getLevel()).isEqualTo(Level.WARN);
        assertThat(keyValue(event, McpServerConnection.KEY_SERVER)).isEqualTo("local");
        assertThat(keyValue(event, McpServerConnection.KEY_CATEGORY)).isEqualTo(McpServerConnection.CATEGORY_LOCAL);
        assertThat(keyValue(event, McpServerConnection.KEY_REACHABLE)).isEqualTo(false);
        assertThat(keyValue(event, McpServerConnection.KEY_DETAIL)).isNotNull();
    }

    /**
     * The token-refresh interceptor for a {@code google-auth} server throws {@link
     * ToolExecutionException} straight out of the interceptor, before any HTTP call happens — even
     * during {@code tools/list} discovery, not just a tool call. {@code describe()} has to treat
     * that exactly like an unreachable server ({@link McpClientException}) rather than let it
     * escape and crash the whole picker.
     */
    @Test
    void describesAnUnreachableServerWhenDiscoveryFailsWithAToolExecutionException() {
        McpWireClient failingWire = mock(McpWireClient.class);
        willThrow(new ToolExecutionException(
                "Google refused to refresh the access token. The refresh token is likely expired "
                        + "or revoked — re-run the one-time authorisation."))
                .given(failingWire).send(any(), any(), any());
        McpProperties.Server configured = new McpProperties.Server("local", "Local", "http://localhost:8080",
                "/mcp", McpProperties.Protocol.STATELESS, null, false);
        McpServerConnection connectionWithFailingAuth =
                new McpServerConnection(configured, failingWire, JsonMapper.builder().build());

        McpServerDescriptor described = connectionWithFailingAuth.describe();

        assertThat(described.id()).isEqualTo("local");
        assertThat(described.reachable()).isFalse();
        assertThat(described.toolCount()).isZero();
        assertThat(described.detail()).isEqualTo(
                "Google refused to refresh the access token. The refresh token is likely expired "
                        + "or revoked — re-run the one-time authorisation.");
    }

    /**
     * A key-value is exported as an OTLP log-record attribute and stored as Loki structured
     * metadata, and Loki rejects the whole entry once that grows past its limit instead of trimming
     * the field — so an unbounded detail would cost exactly the line that reports the outage. The
     * text really is unbounded: an unauthorised remote server answers 403 with its full tool
     * catalogue, which the client exception's message carries verbatim. The descriptor keeps it
     * whole, because that goes to the picker rather than to the backend.
     */
    @Test
    void truncatesTheLoggedDetailWhileTheDescriptorKeepsItWhole() {
        String oversized = "403 Forbidden: " + "x".repeat(50_000);
        McpWireClient failingWire = mock(McpWireClient.class);
        willThrow(new McpClientException(oversized)).given(failingWire).send(any(), any(), any());
        McpProperties.Server configured = new McpProperties.Server("local", "Local", "http://localhost:8080",
                "/mcp", McpProperties.Protocol.STATELESS, null, false);
        McpServerConnection connectionWithOversizedFailure =
                new McpServerConnection(configured, failingWire, JsonMapper.builder().build());

        McpServerDescriptor described = connectionWithOversizedFailure.describe();

        ILoggingEvent event = eventsWithMessage(McpServerConnection.UNREACHABLE_MESSAGE).getFirst();
        assertThat((String) keyValue(event, McpServerConnection.KEY_DETAIL))
                .hasSize(FailureDetail.MAX_DETAIL_CHARACTERS + 1)
                .startsWith("403 Forbidden: ");
        assertThat(described.detail()).isEqualTo(oversized);
    }

    @Test
    void describesAReachableServerWithItsToolCount() {
        server.expect(requestTo(URL)).andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andRespond(withSuccess("""
                        {"jsonrpc":"2.0","id":1,"result":{"resultType":"complete","tools":[
                          {"name":"a","inputSchema":{"type":"object"}},
                          {"name":"b","inputSchema":{"type":"object"}}]}}
                        """, MediaType.APPLICATION_JSON));

        McpServerDescriptor described = connection.describe();

        assertThat(described.reachable()).isTrue();
        assertThat(described.local()).isTrue();
        assertThat(described.toolCount()).isEqualTo(2);
        assertThat(described.protocol()).isEqualTo("STATELESS");
        assertThat(described.detail()).isNull();
    }

    /**
     * DEBUG rather than INFO: the picker describes every configured server on every page view, so
     * the success path is the noisiest line in the application and the least informative one.
     */
    @Test
    void logsAReachableServerAtDebugWithItsToolCount() {
        server.expect(requestTo(URL)).andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andRespond(withSuccess("""
                        {"jsonrpc":"2.0","id":1,"result":{"resultType":"complete","tools":[
                          {"name":"a","inputSchema":{"type":"object"}},
                          {"name":"b","inputSchema":{"type":"object"}}]}}
                        """, MediaType.APPLICATION_JSON));

        connection.describe();

        List<ILoggingEvent> events = eventsWithMessage(McpServerConnection.REACHABLE_MESSAGE);
        assertThat(events).hasSize(1);
        ILoggingEvent event = events.getFirst();
        assertThat(event.getLevel()).isEqualTo(Level.DEBUG);
        assertThat(keyValue(event, McpServerConnection.KEY_SERVER)).isEqualTo("local");
        assertThat(keyValue(event, McpServerConnection.KEY_CATEGORY)).isEqualTo(McpServerConnection.CATEGORY_LOCAL);
        assertThat(keyValue(event, McpServerConnection.KEY_REACHABLE)).isEqualTo(true);
        assertThat(keyValue(event, McpServerConnection.KEY_TOOL_COUNT)).isEqualTo(2);
    }
}
