package com.my.custom.claudepersonalassistant.mcp.domain.tool.google;

import java.util.Map;

import org.springframework.stereotype.Component;

import com.my.custom.claudepersonalassistant.mcp.client.google.CalendarClient;
import com.my.custom.claudepersonalassistant.mcp.config.ConditionalOnGoogleWorkspace;
import com.my.custom.claudepersonalassistant.mcp.domain.McpTool;
import com.my.custom.claudepersonalassistant.mcp.domain.tool.ToolArguments;
import com.my.custom.claudepersonalassistant.mcp.domain.tool.ToolSchema;

/**
 * Removes an event from the calendar.
 *
 * <p>The only destructive tool in the group, and the schema is what keeps it that way: an event id
 * is the sole argument and it is required, so there is no query, no date range and no "matching"
 * to get wrong. The model has to have listed the event first, which means a human saw it.
 */
@Component
@ConditionalOnGoogleWorkspace
class CalendarDeleteEventTool implements McpTool {

    static final String NAME = "calendar_delete_event";

    private final CalendarClient calendar;

    CalendarDeleteEventTool(CalendarClient calendarClient) {
        this.calendar = calendarClient;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String title() {
        return "Delete a calendar event";
    }

    @Override
    public String description() {
        return "Permanently deletes one event from the user's primary Google Calendar. This cannot be "
                + "undone. Takes a single event id, which must come from calendar_list_events — never "
                + "guess it. Call calendar_list_events first and confirm the event with the user before "
                + "deleting.";
    }

    @Override
    public Map<String, Object> inputSchema() {
        return ToolSchema.object()
                .required("event_id", ToolSchema.string(
                        "Event id, exactly as returned by calendar_list_events."))
                .build();
    }

    @Override
    public String execute(Map<String, Object> arguments) {
        String eventId = ToolArguments.requiredText(arguments, "event_id");
        calendar.delete(eventId);
        return "Deleted event %s.".formatted(eventId);
    }
}
