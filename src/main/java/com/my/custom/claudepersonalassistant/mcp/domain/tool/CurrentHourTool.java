package com.my.custom.claudepersonalassistant.mcp.domain.tool;

import java.time.Clock;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;

import org.springframework.stereotype.Component;

import com.my.custom.claudepersonalassistant.mcp.domain.McpTool;

/**
 * Answers with the server's current time.
 *
 * <p>Exists because the model cannot read a clock: asked for the time it correctly refuses,
 * explaining it has no access to real-time information. Routing the question through a tool is
 * what turns that refusal into an answer.
 */
@Component
class CurrentHourTool implements McpTool {

    static final String NAME = "get_current_hour";
    static final DateTimeFormatter FORMAT = DateTimeFormatter.ofPattern("HH:mm");

    private final Clock clock;

    CurrentHourTool(Clock clock) {
        this.clock = clock;
    }

    @Override
    public String name() {
        return NAME;
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
        // The specification's recommended shape for a tool with no parameters: an object that
        // explicitly accepts nothing else.
        return Map.of("type", "object", "additionalProperties", false);
    }

    @Override
    public String execute(Map<String, Object> arguments) {
        ZonedDateTime now = ZonedDateTime.now(clock);
        return "The current time is %s (%s).".formatted(FORMAT.format(now), now.getZone());
    }
}
