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

/**
 * Books a new event on the primary calendar.
 *
 * <p>Times are wall-clock in the server's zone, matching {@code get_current_hour}, so "tomorrow at
 * three" is one lookup and no arithmetic. The end-after-start check is here rather than left to
 * Google because the API's own rejection arrives as an opaque 400.
 */
@Component
@ConditionalOnGoogleWorkspace
class CalendarCreateEventTool implements McpTool {

    static final String NAME = "calendar_create_event";

    private final CalendarClient calendar;
    private final Clock clock;

    CalendarCreateEventTool(CalendarClient calendarClient, Clock mcpClock) {
        this.calendar = calendarClient;
        this.clock = mcpClock;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String title() {
        return "Create a calendar event";
    }

    @Override
    public String description() {
        return "Creates an event on the user's primary Google Calendar. Times are local wall-clock in "
                + "the calendar's own time zone — call get_current_hour first if you need to resolve "
                + "'tomorrow' or 'next Tuesday'. Adding attendees emails them an invitation, so "
                + "confirm the guest list with the user before including one.";
    }

    @Override
    public Map<String, Object> inputSchema() {
        return ToolSchema.object()
                .required("summary", ToolSchema.string("Title of the event."))
                .required("start", ToolSchema.string(
                        "Local start date-time, e.g. 2026-08-14T15:00:00 (no time zone offset)."))
                .required("end", ToolSchema.string(
                        "Local end date-time, e.g. 2026-08-14T16:00:00 (no time zone offset)."))
                .optional("description", ToolSchema.string("Longer description or agenda."))
                .optional("location", ToolSchema.string("Where the event takes place."))
                .optional("attendees", ToolSchema.stringArray(
                        "Email addresses to invite. Each one receives an invitation."))
                .build();
    }

    @Override
    public String execute(Map<String, Object> arguments) {
        String summary = ToolArguments.requiredText(arguments, "summary");
        ZonedDateTime start = ToolArguments.optionalMoment(arguments, "start", clock.getZone());
        ZonedDateTime end = ToolArguments.optionalMoment(arguments, "end", clock.getZone());
        if (start == null || end == null) {
            throw new ToolExecutionException("Both 'start' and 'end' are required.");
        }
        if (!end.isAfter(start)) {
            throw new ToolExecutionException("'end' (%s) must be after 'start' (%s).".formatted(end, start));
        }
        List<String> attendees = ToolArguments.optionalTexts(arguments, "attendees");

        CalendarEvent created = calendar.create(new CalendarClient.EventDraft(
                summary, start, end,
                ToolArguments.optionalText(arguments, "description"),
                ToolArguments.optionalText(arguments, "location"),
                attendees));

        return "Created '%s' (id %s) from %s to %s%s.".formatted(
                created.title(), created.id(), start, end,
                attendees.isEmpty() ? "" : ", inviting " + String.join(", ", attendees));
    }
}
