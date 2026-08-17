package com.my.custom.claudepersonalassistant.mcp.domain.tool.google;

import java.time.Clock;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

import com.my.custom.claudepersonalassistant.mcp.client.google.CalendarClient;
import com.my.custom.claudepersonalassistant.mcp.client.google.CalendarEvent;
import com.my.custom.claudepersonalassistant.mcp.config.ConditionalOnGoogleWorkspace;
import com.my.custom.claudepersonalassistant.mcp.domain.McpTool;
import com.my.custom.claudepersonalassistant.mcp.domain.ToolExecutionException;
import com.my.custom.claudepersonalassistant.mcp.domain.tool.ToolArguments;
import com.my.custom.claudepersonalassistant.mcp.domain.tool.ToolSchema;

/**
 * Changes an existing event.
 *
 * <p>Every field but the id is optional and only what is supplied is sent, so moving a meeting
 * keeps its description and guest list. That is also why the underlying call is a PATCH: a PUT
 * would blank whatever the model happened not to mention.
 */
@Component
@ConditionalOnGoogleWorkspace
class CalendarUpdateEventTool implements McpTool {

    static final String NAME = "calendar_update_event";

    private final CalendarClient calendar;
    private final Clock clock;

    CalendarUpdateEventTool(CalendarClient calendarClient, Clock mcpClock) {
        this.calendar = calendarClient;
        this.clock = mcpClock;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String title() {
        return "Update a calendar event";
    }

    @Override
    public String description() {
        return "Updates an existing event on the user's primary Google Calendar. Only the fields you "
                + "supply are changed; everything else is left alone. Takes an event id from "
                + "calendar_list_events. Times are local wall-clock in the calendar's own time zone.";
    }

    @Override
    public Map<String, Object> inputSchema() {
        return ToolSchema.object()
                .required("event_id", ToolSchema.string("Event id, as returned by calendar_list_events."))
                .optional("summary", ToolSchema.string("New title."))
                .optional("start", ToolSchema.string("New local start date-time, e.g. 2026-08-14T15:00:00."))
                .optional("end", ToolSchema.string("New local end date-time, e.g. 2026-08-14T16:00:00."))
                .optional("description", ToolSchema.string("New description."))
                .optional("location", ToolSchema.string("New location."))
                .build();
    }

    @Override
    public String execute(Map<String, Object> arguments) {
        String eventId = ToolArguments.requiredText(arguments, "event_id");
        ZonedDateTime start = ToolArguments.optionalMoment(arguments, "start", clock.getZone());
        ZonedDateTime end = ToolArguments.optionalMoment(arguments, "end", clock.getZone());
        if (start != null && end != null && !end.isAfter(start)) {
            throw new ToolExecutionException("'end' (%s) must be after 'start' (%s).".formatted(end, start));
        }

        CalendarEvent updated = calendar.update(eventId, new CalendarClient.EventDraft(
                ToolArguments.optionalText(arguments, "summary"), start, end,
                ToolArguments.optionalText(arguments, "description"),
                ToolArguments.optionalText(arguments, "location"),
                List.of()));

        return "Updated '%s' (id %s). It now runs %s.".formatted(
                updated.title(), updated.id(), updated.timeRange());
    }
}
