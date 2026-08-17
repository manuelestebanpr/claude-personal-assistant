package com.my.custom.claudepersonalassistant.mcp.client;

import java.util.Map;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The one thing the wire-client tests cannot pin: the exact bytes. Jackson serializes record
 * components in declaration order, which is what makes the request body deterministic — the
 * {@code Map.of} it replaced randomised its iteration order per JVM start, so an otherwise
 * unchanged request could reorder between restarts.
 */
class JsonRpcRequestBodyTest {

    private final JsonMapper mapper = JsonMapper.builder().build();

    @Test
    void serializesComponentsInWireOrder() {
        String json = mapper.writeValueAsString(
                JsonRpcRequestBody.of(7, "tools/list", Map.of()));

        assertThat(json).isEqualTo(
                "{\"jsonrpc\":\"2.0\",\"id\":7,\"method\":\"tools/list\",\"params\":{}}");
    }

    @Test
    void aNotificationCarriesNeitherIdNorParams() {
        String json = mapper.writeValueAsString(
                JsonRpcRequestBody.notification("notifications/initialized"));

        assertThat(json).isEqualTo(
                "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}");
    }
}
