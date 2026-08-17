package com.my.custom.claudepersonalassistant.mcp.client.google;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import org.springframework.util.StringUtils;

/**
 * A calendar event as the API returns it.
 *
 * <p>{@link EventTime} carries either {@code dateTime} or {@code date} and never both: an all-day
 * event has no time of day, and reading only {@code dateTime} would render every all-day event as
 * blank.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record CalendarEvent(
        String id,
        String summary,
        String description,
        String location,
        EventTime start,
        EventTime end,
        List<Attendee> attendees) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record EventTime(String dateTime, String date) {

        /** The instant if timed, the day if all-day. */
        public String display() {
            if (StringUtils.hasText(dateTime)) {
                return dateTime;
            }
            return StringUtils.hasText(date) ? date + " (all day)" : "unscheduled";
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Attendee(String email) {
    }

    public String title() {
        return StringUtils.hasText(summary) ? summary : "(no title)";
    }

    public String timeRange() {
        String from = start == null ? "unscheduled" : start.display();
        String to = end == null ? "unscheduled" : end.display();
        return from + " → " + to;
    }
}
