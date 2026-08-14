package com.my.custom.claudepersonalassistant.mcp.client.google;

import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;

import com.my.custom.claudepersonalassistant.mcp.config.GoogleWorkspaceProperties;
import com.my.custom.claudepersonalassistant.mcp.domain.ToolExecutionException;

/**
 * The slice of the Google Calendar REST API the tools need.
 */
public class CalendarClient {

    private final RestClient restClient;
    private final GoogleAccessTokens tokens;
    private final String calendarId;

    CalendarClient(RestClient calendarRestClient, GoogleAccessTokens tokens,
            GoogleWorkspaceProperties properties) {
        this.restClient = calendarRestClient;
        this.tokens = tokens;
        this.calendarId = properties.endpoints().calendarId();
    }

    /**
     * Events overlapping a window, earliest first.
     *
     * <p>{@code singleEvents=true} expands recurrences into their occurrences — without it a weekly
     * stand-up comes back once, as the rule that generates it, and "what's on this week" is wrong.
     * {@code orderBy=startTime} is only accepted alongside it, which is the API telling you the
     * same thing.
     */
    public List<CalendarEvent> events(Instant from, Instant to, int maxResults) {
        EventList list = GoogleApiCalls.call("Calendar list", () -> restClient.get()
                .uri(builder -> builder.path("/calendars/{calendarId}/events")
                        .queryParam("timeMin", from.toString())
                        .queryParam("timeMax", to.toString())
                        .queryParam("singleEvents", true)
                        .queryParam("orderBy", "startTime")
                        .queryParam("maxResults", maxResults)
                        .build(calendarId))
                .header(HttpHeaders.AUTHORIZATION, bearer())
                .retrieve()
                .body(EventList.class));
        return list == null || list.items() == null ? List.of() : list.items();
    }

    public CalendarEvent create(EventDraft draft) {
        return GoogleApiCalls.call("Calendar event creation", () -> restClient.post()
                .uri(builder -> builder.path("/calendars/{calendarId}/events").build(calendarId))
                .header(HttpHeaders.AUTHORIZATION, bearer())
                .contentType(MediaType.APPLICATION_JSON)
                .body(body(draft))
                .retrieve()
                .body(CalendarEvent.class));
    }

    /**
     * Applies only the fields the caller set. PATCH rather than PUT so an update that mentions the
     * time does not silently erase the description and guest list.
     */
    public CalendarEvent update(String eventId, EventDraft draft) {
        Map<String, Object> changes = body(draft);
        if (changes.isEmpty()) {
            throw new ToolExecutionException("Nothing to update: no fields were supplied.");
        }
        return GoogleApiCalls.call("Calendar event update", () -> restClient.patch()
                .uri(builder -> builder.path("/calendars/{calendarId}/events/{eventId}")
                        .build(calendarId, eventId))
                .header(HttpHeaders.AUTHORIZATION, bearer())
                .contentType(MediaType.APPLICATION_JSON)
                .body(changes)
                .retrieve()
                .body(CalendarEvent.class));
    }

    private Map<String, Object> body(EventDraft draft) {
        Map<String, Object> body = new LinkedHashMap<>();
        if (StringUtils.hasText(draft.summary())) {
            body.put("summary", draft.summary());
        }
        if (StringUtils.hasText(draft.description())) {
            body.put("description", draft.description());
        }
        if (StringUtils.hasText(draft.location())) {
            body.put("location", draft.location());
        }
        if (draft.start() != null) {
            body.put("start", time(draft.start()));
        }
        if (draft.end() != null) {
            body.put("end", time(draft.end()));
        }
        if (draft.attendees() != null && !draft.attendees().isEmpty()) {
            body.put("attendees", draft.attendees().stream()
                    .map(email -> Map.of("email", email))
                    .toList());
        }
        return body;
    }

    /**
     * Sends the offset and the zone id together: the offset alone pins the instant, and the zone id
     * is what lets Google keep a recurring or later-moved event correct across a DST boundary.
     */
    private Map<String, Object> time(ZonedDateTime moment) {
        return Map.of(
                "dateTime", moment.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME),
                "timeZone", moment.getZone().getId());
    }

    private String bearer() {
        return "Bearer " + tokens.current();
    }

    /**
     * The mutable fields of an event. A {@code null} means "leave alone" on update and "omit" on
     * create, so one shape serves both calls.
     */
    public record EventDraft(
            String summary,
            ZonedDateTime start,
            ZonedDateTime end,
            String description,
            String location,
            List<String> attendees) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record EventList(List<CalendarEvent> items) {
    }
}
