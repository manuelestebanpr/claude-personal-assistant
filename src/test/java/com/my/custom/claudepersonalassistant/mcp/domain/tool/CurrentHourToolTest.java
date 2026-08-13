package com.my.custom.claudepersonalassistant.mcp.domain.tool;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CurrentHourToolTest {

    private final Clock clock = Clock.fixed(Instant.parse("2026-08-12T19:07:40Z"), ZoneId.of("Europe/Madrid"));
    private final CurrentHourTool tool = new CurrentHourTool(clock);

    @Test
    void reportsTheTimeInTheServerZone() {
        // 19:07 UTC is 21:07 in Madrid: the zone has to be applied, and stated, or the answer is
        // ambiguous to whoever reads it.
        assertThat(tool.execute(Map.of())).isEqualTo("The current time is 21:07 (Europe/Madrid).");
    }

    @Test
    void ignoresUnexpectedArguments() {
        assertThat(tool.execute(Map.of("unexpected", "value"))).contains("21:07");
    }

    @Test
    void declaresItselfAsTakingNoParameters() {
        assertThat(tool.name()).isEqualTo("get_current_hour");
        assertThat(tool.inputSchema())
                .containsEntry("type", "object")
                .containsEntry("additionalProperties", false);
    }
}
