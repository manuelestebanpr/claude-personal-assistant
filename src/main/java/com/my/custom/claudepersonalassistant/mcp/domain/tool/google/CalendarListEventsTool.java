package com.my.custom.claudepersonalassistant.mcp.domain.tool.google;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

import com.my.custom.claudepersonalassistant.mcp.client.google.CalendarClient;
import com.my.custom.claudepersonalassistant.mcp.client.google.CalendarEvent;
import com.my.custom.claudepersonalassistant.mcp.config.ConditionalOnGoogleWorkspace;
import com.my.custom.claudepersonalassistant.mcp.config.GoogleWorkspaceProperties;
import com.my.custom.claudepersonalassistant.mcp.domain.McpTool;
import com.my.custom.claudepersonalassistant.mcp.domain.tool.ToolArguments;
import com.my.custom.claudepersonalassistant.mcp.domain.tool.ToolSchema;

/**
 * Lists what is on the calendar over the next few days.
 *
 * <p>Takes a day count rather than a pair of timestamps, and resolves "now" from the same
 * {@link Clock} as {@code get_current_hour}: the model cannot read a clock, so a tool that demanded
 * an absolute window would be asking it to invent one.
 */
@Component
@ConditionalOnGoogleWorkspace
class CalendarListEventsTool implements McpTool {

    static final String NAME = "calendar_list_events";

    private static final int DEFAULT_DAYS = 7;
    private static final int MAX_DAYS = 90;

    private final CalendarClient calendar;
    private final GoogleWorkspaceProperties.Limits limits;
    private final Clock clock;

    CalendarListEventsTool(CalendarClient calendarClient, GoogleWorkspaceProperties properties,
            Clock mcpClock) {
        this.calendar = calendarClient;
        this.limits = properties.limits();
        this.clock = mcpClock;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String title() {
        return "List calendar events";
    }

    @Override
    public String description() {
        return "Lists upcoming events on the user's primary Google Calendar, earliest first, starting "
                + "from now. Recurring events are expanded into their individual occurrences. Returns "
                + "event ids — pass one to calendar_update_event to change it.";
    }

    @Override
    public Map<String, Object> inputSchema() {
        return ToolSchema.object()
                .optional("days_ahead", ToolSchema.integer(
                        "How many days forward to look from now. Defaults to %d.".formatted(DEFAULT_DAYS),
                        1, MAX_DAYS))
                .optional("max_results", ToolSchema.integer(
                        "How many events to return. Defaults to %d.".formatted(limits.defaultResults()),
                        1, limits.maxResults()))
                .build();
    }

    @Override
    public String execute(Map<String, Object> arguments) {
        int daysAhead = ToolArguments.boundedNumber(arguments, "days_ahead", DEFAULT_DAYS, MAX_DAYS);
        int maxResults = ToolArguments.boundedNumber(arguments, "max_results",
                limits.defaultResults(), limits.maxResults());

        Instant from = clock.instant();
        Instant to = from.plus(Duration.ofDays(daysAhead));
        List<CalendarEvent> events = calendar.events(from, to, maxResults);
        if (events.isEmpty()) {
            return "Nothing on the calendar in the next %d day(s).".formatted(daysAhead);
        }

        StringBuilder rendered = new StringBuilder(
                "%d event(s) in the next %d day(s):%n".formatted(events.size(), daysAhead));
        for (CalendarEvent event : events) {
            rendered.append("%n- %s%n".formatted(event.title()))
                    .append("  When: %s%n".formatted(event.timeRange()))
                    .append("  id: %s%n".formatted(event.id()));
            if (event.location() != null && !event.location().isBlank()) {
                rendered.append("  Where: %s%n".formatted(event.location()));
            }
            if (event.attendees() != null && !event.attendees().isEmpty()) {
                rendered.append("  Guests: %s%n".formatted(event.attendees().stream()
                        .map(CalendarEvent.Attendee::email)
                        .reduce((first, second) -> first + ", " + second)
                        .orElse("")));
            }
        }
        return rendered.toString();
    }
}
