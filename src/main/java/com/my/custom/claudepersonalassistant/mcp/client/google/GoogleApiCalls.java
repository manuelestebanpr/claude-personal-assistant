package com.my.custom.claudepersonalassistant.mcp.client.google;

import java.util.function.Supplier;

import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import com.my.custom.claudepersonalassistant.mcp.domain.ToolExecutionException;

/**
 * Turns a failed Google API call into something the model can act on.
 *
 * <p>Every failure here is a tool that <em>ran and failed</em>, so it becomes a result carrying
 * {@code isError} rather than a JSON-RPC error — the model reads the reason and either narrows the
 * request or tells the user. That distinction is why the reason has to survive into the message:
 * "insufficient authentication scopes" is recoverable by asking for different data, a 401 is not.
 */
final class GoogleApiCalls {

    private static final int MAX_UPSTREAM_DETAIL = 300;

    private GoogleApiCalls() {
    }

    static <T> T call(String operationLabel, Supplier<T> request) {
        try {
            return request.get();
        }
        catch (RestClientResponseException failed) {
            throw new ToolExecutionException("%s failed: HTTP %d. %s"
                    .formatted(operationLabel, failed.getStatusCode().value(), detail(failed)), failed);
        }
        catch (RestClientException unreachable) {
            throw new ToolExecutionException("%s failed: %s".formatted(operationLabel, unreachable.getMessage()),
                    unreachable);
        }
    }

    /**
     * Google's error body is small and specific ("Request had insufficient authentication scopes"),
     * which is exactly operationLabel the model needs — but it is still untrusted upstream text landing in
     * the context window, so it is capped.
     */
    private static String detail(RestClientResponseException failed) {
        String body = failed.getResponseBodyAsString();
        if (body == null || body.isBlank()) {
            return failed.getStatusText();
        }
        return body.length() <= MAX_UPSTREAM_DETAIL ? body : body.substring(0, MAX_UPSTREAM_DETAIL) + "…";
    }
}
