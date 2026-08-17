package com.my.custom.claudepersonalassistant.mcp.client;

/**
 * Cuts a transport failure message down to a length that is safe to attach as a log key-value.
 *
 * <p>Not cosmetic. Every key-value on a record leaves this application as an OTLP log-record
 * attribute and lands in Loki as structured metadata, and Loki rejects the <em>whole entry</em>
 * with a 400 once that exceeds {@code max_structured_metadata_size} — it does not trim the
 * offending field. These messages are genuinely unbounded: an MCP server answering an unauthorised
 * request with a 403 still returns its complete tool catalogue, and Spring's
 * {@code RestClientResponseException} interpolates that untruncated body into its own message. So
 * the one WARN line whose entire job is to report a server unreachable is exactly the line that
 * would vanish, leaving the dashboard reading as if the server were fine.
 *
 * <p>The cap is a constant rather than a property for the same reason the appender's stack-trace
 * cap is: the limit belongs to the backend, and this is the layer that knows which field is at
 * risk. A few hundred characters is enough to tell a 403 from a connection refusal from a protocol
 * mismatch; the untruncated text still reaches the UI through
 * {@link com.my.custom.claudepersonalassistant.mcp.api.McpServerDescriptor#detail()}, which is not
 * an export surface.
 */
final class FailureDetail {

    static final int MAX_DETAIL_CHARACTERS = 512;

    private static final String ELLIPSIS = "…";

    private FailureDetail() {
    }

    static String of(Throwable failure) {
        String message = failure.getMessage();
        if (message == null || message.length() <= MAX_DETAIL_CHARACTERS) {
            return message;
        }
        return message.substring(0, MAX_DETAIL_CHARACTERS) + ELLIPSIS;
    }
}
