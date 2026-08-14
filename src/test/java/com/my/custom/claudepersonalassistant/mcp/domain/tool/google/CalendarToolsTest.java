package com.my.custom.claudepersonalassistant.mcp.domain.tool.google;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.my.custom.claudepersonalassistant.mcp.client.google.CalendarClient;
import com.my.custom.claudepersonalassistant.mcp.client.google.CalendarEvent;
import com.my.custom.claudepersonalassistant.mcp.config.GoogleWorkspaceProperties;
import com.my.custom.claudepersonalassistant.mcp.domain.ToolExecutionException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CalendarToolsTest {

    private static final ZoneId ZONE = ZoneId.of("America/Bogota");

    private final CalendarClient calendar = mock(CalendarClient.class);
    private final Clock clock = Clock.fixed(Instant.parse("2026-08-13T15:00:00Z"), ZONE);
    private final GoogleWorkspaceProperties properties = new GoogleWorkspaceProperties(
            true, "id", "secret", "refresh",
            new GoogleWorkspaceProperties.Endpoints("t", "g", "c", "primary"),
            new GoogleWorkspaceProperties.Limits(2, 3, 4000));

    private final CalendarListEventsTool list = new CalendarListEventsTool(calendar, properties, clock);
    private final CalendarCreateEventTool create = new CalendarCreateEventTool(calendar, clock);
    private final CalendarUpdateEventTool update = new CalendarUpdateEventTool(calendar, clock);

    /** "Now" comes from the same clock as get_current_hour — the model cannot supply one. */
    @Test
    void listAsksForAWindowStartingAtTheServersNow() {
        when(calendar.events(any(), any(), anyInt())).thenReturn(List.of());

        list.execute(Map.of("days_ahead", 3));

        verify(calendar).events(Instant.parse("2026-08-13T15:00:00Z"),
                Instant.parse("2026-08-16T15:00:00Z"), 2);
    }

    @Test
    void listRendersTitleWindowAndIdSoAnEventCanBeUpdated() {
        when(calendar.events(any(), any(), anyInt())).thenReturn(List.of(new CalendarEvent(
                "e1", "Stand-up", null, "Room 2", null,
                new CalendarEvent.EventTime("2026-08-14T09:00:00-05:00", null, "America/Bogota"),
                new CalendarEvent.EventTime("2026-08-14T09:15:00-05:00", null, "America/Bogota"),
                List.of(new CalendarEvent.Attendee("alice@example.com", "accepted")))));

        String rendered = list.execute(Map.of());

        assertThat(rendered)
                .contains("Stand-up")
                .contains("id: e1")
                .contains("2026-08-14T09:00:00-05:00 → 2026-08-14T09:15:00-05:00")
                .contains("Room 2")
                .contains("alice@example.com");
    }

    @Test
    void listHandlesAnAllDayEventWithNoTimeOfDay() {
        when(calendar.events(any(), any(), anyInt())).thenReturn(List.of(new CalendarEvent(
                "e2", "Public holiday", null, null, null,
                new CalendarEvent.EventTime(null, "2026-08-17", null),
                new CalendarEvent.EventTime(null, "2026-08-18", null), null)));

        assertThat(list.execute(Map.of())).contains("2026-08-17 (all day)");
    }

    @Test
    void listSaysSoWhenTheCalendarIsEmpty() {
        when(calendar.events(any(), any(), anyInt())).thenReturn(List.of());

        assertThat(list.execute(Map.of())).contains("Nothing on the calendar");
    }

    /**
     * The model is given wall-clock times because that is the shape get_current_hour hands it;
     * turning them into an instant is this tool's job, and getting it wrong books the wrong hour.
     */
    @Test
    void createResolvesWallClockTimesIntoTheCalendarsZone() {
        when(calendar.create(any())).thenReturn(event("e1", "Review"));
        ArgumentCaptor<CalendarClient.EventDraft> draft =
                ArgumentCaptor.forClass(CalendarClient.EventDraft.class);

        create.execute(Map.of("summary", "Review",
                "start", "2026-08-14T15:00:00", "end", "2026-08-14T16:00:00",
                "attendees", List.of("alice@example.com")));

        verify(calendar).create(draft.capture());
        assertThat(draft.getValue().start())
                .isEqualTo(ZonedDateTime.of(2026, 8, 14, 15, 0, 0, 0, ZONE));
        assertThat(draft.getValue().end().toInstant()).isEqualTo(Instant.parse("2026-08-14T21:00:00Z"));
        assertThat(draft.getValue().attendees()).containsExactly("alice@example.com");
    }

    @Test
    void createRefusesAnEventThatEndsBeforeItStarts() {
        assertThatThrownBy(() -> create.execute(Map.of("summary", "Review",
                "start", "2026-08-14T16:00:00", "end", "2026-08-14T15:00:00")))
                .isInstanceOf(ToolExecutionException.class)
                .hasMessageContaining("must be after");
    }

    @Test
    void createRejectsATimeItCannotParseWithAnExampleOfOneItCan() {
        assertThatThrownBy(() -> create.execute(Map.of("summary", "Review",
                "start", "next tuesday", "end", "2026-08-14T15:00:00")))
                .isInstanceOf(ToolExecutionException.class)
                .hasMessageContaining("2026-08-14T15:00:00");
    }

    /** Moving a meeting must not blank its description — hence PATCH and nulls for "leave alone". */
    @Test
    void updateSendsOnlyTheFieldsThatWereSupplied() {
        when(calendar.update(eq("e1"), any())).thenReturn(event("e1", "Review"));
        ArgumentCaptor<CalendarClient.EventDraft> draft =
                ArgumentCaptor.forClass(CalendarClient.EventDraft.class);

        update.execute(Map.of("event_id", "e1", "start", "2026-08-14T17:00:00"));

        verify(calendar).update(eq("e1"), draft.capture());
        assertThat(draft.getValue().start()).isEqualTo(ZonedDateTime.of(2026, 8, 14, 17, 0, 0, 0, ZONE));
        assertThat(draft.getValue().summary()).isNull();
        assertThat(draft.getValue().description()).isNull();
        assertThat(draft.getValue().end()).isNull();
    }

    /**
     * The form is rendered in schema order, and a model prompt cache keys on the serialised tool
     * list — so a property order that reshuffles between restarts is a real cost, not a cosmetic
     * one. {@code Map.of}/{@code Map.copyOf} both randomise it per JVM start.
     */
    @Test
    void declaresArgumentsInAStableOrder() {
        assertThat(propertyOrder(create.inputSchema()))
                .containsExactly("summary", "start", "end", "description", "location", "attendees");
        assertThat(propertyOrder(update.inputSchema()))
                .containsExactly("event_id", "summary", "start", "end", "description", "location");
    }

    @SuppressWarnings("unchecked")
    private List<String> propertyOrder(Map<String, Object> schema) {
        return List.copyOf(((Map<String, Object>) schema.get("properties")).keySet());
    }

    @Test
    void toolsKeepTheirPublishedNamesAndClosedSchemas() {
        assertThat(list.name()).isEqualTo("calendar_list_events");
        assertThat(create.name()).isEqualTo("calendar_create_event");
        assertThat(update.name()).isEqualTo("calendar_update_event");
        assertThat(create.inputSchema()).containsEntry("required", List.of("summary", "start", "end"));
        assertThat(update.inputSchema()).containsEntry("required", List.of("event_id"));
        assertThat(list.inputSchema()).containsEntry("additionalProperties", false);
    }

    private CalendarEvent event(String id, String summary) {
        return new CalendarEvent(id, summary, null, null, null,
                new CalendarEvent.EventTime("2026-08-14T15:00:00-05:00", null, ZONE.getId()),
                new CalendarEvent.EventTime("2026-08-14T16:00:00-05:00", null, ZONE.getId()), null);
    }
}
