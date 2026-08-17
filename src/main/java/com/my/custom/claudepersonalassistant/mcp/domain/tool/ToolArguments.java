package com.my.custom.claudepersonalassistant.mcp.domain.tool;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.springframework.util.StringUtils;

import com.my.custom.claudepersonalassistant.mcp.domain.ToolExecutionException;

/**
 * Reads a tool's arguments off the wire.
 *
 * <p>The schema tells the model what to send; nothing enforces that it did. Everything here is
 * therefore defensive, and a violation becomes a {@link ToolExecutionException} — a result the
 * model can read and correct on the next turn, rather than a stack trace it never sees.
 *
 * <p>Lives in {@code domain.tool} rather than beside one tool group for the same reason as
 * {@link ToolSchema}: it is how every group reads its arguments, not how one of them does.
 */
public final class ToolArguments {

    private ToolArguments() {
    }

    public static String requiredText(Map<String, Object> arguments, String name) {
        String value = optionalText(arguments, name);
        if (!StringUtils.hasText(value)) {
            throw new ToolExecutionException("Missing required argument '%s'.".formatted(name));
        }
        return value;
    }

    public static String optionalText(Map<String, Object> arguments, String name) {
        Object value = arguments == null ? null : arguments.get(name);
        return value == null ? null : value.toString().trim();
    }

    /**
     * Clamps rather than rejects. A model that asks for 500 results wants "as many as you can" —
     * answering with the ceiling is more useful than an error it has to recover from, and the
     * ceiling is what protects the context window either way.
     */
    public static int boundedNumber(Map<String, Object> arguments, String name, int fallback, int maximum) {
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

    /**
     * Reads money or a weight.
     *
     * <p>Parsed from {@code toString()} rather than {@code Number.doubleValue()} even when the JSON
     * already gave us a number: a price that arrives as {@code 45190.00} must stay {@code 45190.00}
     * and not become {@code 45189.999999999996}. {@code BigDecimal(String)} is the only route that
     * never visits a binary float.
     */
    public static BigDecimal requiredDecimal(Map<String, Object> arguments, String name, BigDecimal minimum) {
        BigDecimal value = optionalDecimal(arguments, name, null);
        if (value == null) {
            throw new ToolExecutionException("Missing required argument '%s'.".formatted(name));
        }
        if (value.compareTo(minimum) < 0) {
            throw new ToolExecutionException(
                    "Argument '%s' must be at least %s, got %s.".formatted(name, minimum.toPlainString(),
                            value.toPlainString()));
        }
        return value;
    }

    public static BigDecimal optionalDecimal(Map<String, Object> arguments, String name, BigDecimal fallback) {
        String value = optionalText(arguments, name);
        if (!StringUtils.hasText(value)) {
            return fallback;
        }
        try {
            return new BigDecimal(value);
        }
        catch (NumberFormatException notANumber) {
            throw new ToolExecutionException(
                    "Argument '%s' must be a number, got '%s'.".formatted(name, value));
        }
    }

    public static List<String> optionalTexts(Map<String, Object> arguments, String name) {
        Object value = arguments == null ? null : arguments.get(name);
        if (value == null) {
            return List.of();
        }
        if (value instanceof List<?> list) {
            return list.stream().filter(Objects::nonNull).map(Object::toString).toList();
        }
        // Models routinely send a single value where an array is declared; accepting it costs
        // nothing and saves a wasted turn.
        return List.of(value.toString());
    }

    /**
     * Reads a required list of identifiers, accepting a bare one where an array is declared for the
     * same reason {@link #optionalTexts} does — "delete grocery 7" is one call, not one call with a
     * malformed argument.
     */
    public static List<Long> requiredIdentifiers(Map<String, Object> arguments, String name) {
        List<String> raw = optionalTexts(arguments, name);
        if (raw.isEmpty()) {
            throw new ToolExecutionException("Missing required argument '%s'.".formatted(name));
        }
        return raw.stream().map(value -> parseIdentifier(name, value)).toList();
    }

    /**
     * Reads a required list of objects — the rows of a bulk call.
     *
     * <p>A single object where an array is declared is accepted, again to spare a turn. Anything
     * else in the list is rejected outright rather than coerced: a string where a row belongs means
     * the model misunderstood the schema, and silently dropping it would import a shopping list
     * missing an item nobody notices.
     */
    @SuppressWarnings("unchecked")
    public static List<Map<String, Object>> requiredObjects(Map<String, Object> arguments, String name) {
        Object value = arguments == null ? null : arguments.get(name);
        List<?> candidates = switch (value) {
            case null -> List.of();
            case List<?> list -> list;
            case Map<?, ?> single -> List.of(single);
            default -> throw new ToolExecutionException(
                    "Argument '%s' must be a list of objects, got '%s'.".formatted(name, value));
        };
        if (candidates.isEmpty()) {
            throw new ToolExecutionException("Argument '%s' must contain at least one entry.".formatted(name));
        }
        return candidates.stream()
                .map(candidate -> {
                    if (candidate instanceof Map<?, ?> row) {
                        return (Map<String, Object>) row;
                    }
                    throw new ToolExecutionException(
                            "Every entry of '%s' must be an object, got '%s'.".formatted(name, candidate));
                })
                .toList();
    }

    /**
     * Parses a wall-clock date-time into the server's zone.
     *
     * <p>Local rather than offset input by design: the model is told the current time by
     * {@code get_current_hour} in this same zone, so asking it to also compute a UTC offset would
     * introduce an arithmetic step — and off-by-one-hour meeting invitations.
     */
    public static ZonedDateTime optionalMoment(Map<String, Object> arguments, String name, ZoneId zone) {
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

    private static long parseIdentifier(String name, String value) {
        try {
            // A model that read an id out of a listing sends it back as it saw it, which for a
            // JSON number round-tripped through a double is "7.0" rather than "7".
            return new BigDecimal(value.trim()).longValueExact();
        }
        catch (ArithmeticException | NumberFormatException notAnIdentifier) {
            throw new ToolExecutionException(
                    "Every entry of '%s' must be a whole-number id, got '%s'.".formatted(name, value));
        }
    }
}
