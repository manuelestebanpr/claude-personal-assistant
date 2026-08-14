package com.my.custom.claudepersonalassistant.mcp.domain.tool.google;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Map;

import org.springframework.util.StringUtils;

import com.my.custom.claudepersonalassistant.mcp.domain.ToolExecutionException;

/**
 * Reads a tool's arguments off the wire.
 *
 * <p>The schema tells the model what to send; nothing enforces that it did. Everything here is
 * therefore defensive, and a violation becomes a {@link ToolExecutionException} — a result the
 * model can read and correct on the next turn, rather than a stack trace it never sees.
 */
final class ToolArguments {

    private ToolArguments() {
    }

    static String requiredText(Map<String, Object> arguments, String name) {
        String value = optionalText(arguments, name);
        if (!StringUtils.hasText(value)) {
            throw new ToolExecutionException("Missing required argument '%s'.".formatted(name));
        }
        return value;
    }

    static String optionalText(Map<String, Object> arguments, String name) {
        Object value = arguments == null ? null : arguments.get(name);
        return value == null ? null : value.toString().trim();
    }

    /**
     * Clamps rather than rejects. A model that asks for 500 results wants "as many as you can" —
     * answering with the ceiling is more useful than an error it has to recover from, and the
     * ceiling is what protects the context window either way.
     */
    static int boundedNumber(Map<String, Object> arguments, String name, int fallback, int maximum) {
        Object value = arguments == null ? null : arguments.get(name);
        if (value == null) {
            return fallback;
        }
        int requested;
        if (value instanceof Number number) {
            requested = number.intValue();
        }
        else {
            try {
                requested = Integer.parseInt(value.toString().trim());
            }
            catch (NumberFormatException notANumber) {
                throw new ToolExecutionException(
                        "Argument '%s' must be a number, got '%s'.".formatted(name, value));
            }
        }
        return Math.clamp(requested, 1, maximum);
    }

    static List<String> optionalTexts(Map<String, Object> arguments, String name) {
        Object value = arguments == null ? null : arguments.get(name);
        if (value == null) {
            return List.of();
        }
        if (value instanceof List<?> list) {
            return list.stream().filter(java.util.Objects::nonNull).map(Object::toString).toList();
        }
        // Models routinely send a single value where an array is declared; accepting it costs
        // nothing and saves a wasted turn.
        return List.of(value.toString());
    }

    /**
     * Parses a wall-clock date-time into the server's zone.
     *
     * <p>Local rather than offset input by design: the model is told the current time by
     * {@code get_current_hour} in this same zone, so asking it to also compute a UTC offset would
     * introduce an arithmetic step — and off-by-one-hour meeting invitations.
     */
    static ZonedDateTime optionalMoment(Map<String, Object> arguments, String name, ZoneId zone) {
        String value = optionalText(arguments, name);
        if (!StringUtils.hasText(value)) {
            return null;
        }
        try {
            return LocalDateTime.parse(value).atZone(zone);
        }
        catch (DateTimeParseException unparseable) {
            throw new ToolExecutionException(
                    "Argument '%s' must be a local date-time like 2026-08-14T15:00:00, got '%s'."
                            .formatted(name, value));
        }
    }
}
