package com.my.custom.claudepersonalassistant.assistant.error;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import com.anthropic.errors.AnthropicIoException;
import com.anthropic.errors.AnthropicRetryableException;
import com.anthropic.errors.AnthropicServiceException;
import com.anthropic.errors.BadRequestException;
import com.anthropic.errors.InternalServerException;
import com.anthropic.errors.NotFoundException;
import com.anthropic.errors.PermissionDeniedException;
import com.anthropic.errors.RateLimitException;
import com.anthropic.errors.UnauthorizedException;
import com.anthropic.errors.UnprocessableEntityException;
import com.anthropic.models.ErrorType;

import org.springframework.stereotype.Component;

import com.my.custom.claudepersonalassistant.assistant.dto.ClassifiedError;
import com.my.custom.claudepersonalassistant.assistant.dto.ErrorClassification;

/**
 * Classifies streaming failures by walking the cause chain for Anthropic SDK exceptions,
 * which propagate unwrapped in Spring AI 2.0.
 */
@Component
public class AnthropicErrorClassifier {

    private static final int HTTP_TOO_MANY_REQUESTS = 429;
    private static final int HTTP_SERVER_ERROR_FLOOR = 500;

    /**
     * Searches causes first and only then suppressed exceptions, so an error whose own chain is
     * classifiable is never overruled by something merely attached to it.
     *
     * <p>Suppressed exceptions are searched at all because a failing {@code onError}-side callback
     * can displace the real error: Reactor's {@code Operators.onOperatorError} makes whatever the
     * callback threw the propagated error and demotes the original to a suppressed one. A tracing
     * handler or log appender blowing up would otherwise cost us the classification — the retry
     * affordance in the UI and the {@code classification} tag on {@code assistant.stream.errors}
     * both read from it.
     */
    public ClassifiedError classify(Throwable error) {
        Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        Deque<Throwable> pending = new ArrayDeque<>();
        pending.add(error);
        while (!pending.isEmpty()) {
            List<Throwable> suppressed = new ArrayList<>();
            for (Throwable current = pending.poll(); current != null && seen.add(current);
                    current = current.getCause()) {
                ClassifiedError classified = classifyCause(current);
                if (classified != null) {
                    return classified;
                }
                Collections.addAll(suppressed, current.getSuppressed());
            }
            pending.addAll(suppressed);
        }
        return new ClassifiedError(ErrorClassification.UNKNOWN, null, null, messageOf(error));
    }

    private ClassifiedError classifyCause(Throwable cause) {
        return switch (cause) {
            case RateLimitException e -> fromService(ErrorClassification.RETRYABLE, e);
            case InternalServerException e -> fromService(ErrorClassification.RETRYABLE, e);
            case BadRequestException e -> fromService(ErrorClassification.TERMINAL, e);
            case UnauthorizedException e -> fromService(ErrorClassification.TERMINAL, e);
            case PermissionDeniedException e -> fromService(ErrorClassification.TERMINAL, e);
            case NotFoundException e -> fromService(ErrorClassification.TERMINAL, e);
            case UnprocessableEntityException e -> fromService(ErrorClassification.TERMINAL, e);
            case AnthropicServiceException e -> fromService(byStatusCode(e.statusCode()), e);
            case AnthropicIoException e -> new ClassifiedError(ErrorClassification.RETRYABLE, null, null, messageOf(e));
            case AnthropicRetryableException e ->
                    new ClassifiedError(ErrorClassification.RETRYABLE, null, null, messageOf(e));
            default -> null;
        };
    }

    private ErrorClassification byStatusCode(int statusCode) {
        return (statusCode >= HTTP_SERVER_ERROR_FLOOR || statusCode == HTTP_TOO_MANY_REQUESTS)
                ? ErrorClassification.RETRYABLE
                : ErrorClassification.TERMINAL;
    }

    private ClassifiedError fromService(ErrorClassification classification, AnthropicServiceException exception) {
        return new ClassifiedError(classification, exception.statusCode(), errorTypeOf(exception),
                messageOf(exception));
    }

    private String errorTypeOf(AnthropicServiceException exception) {
        // errorType() parses the response body; guard against nulls from mocked exceptions too.
        Optional<ErrorType> errorType = exception.errorType();
        return errorType.isEmpty() ? null : errorType.map(ErrorType::asString).orElse(null);
    }

    private String messageOf(Throwable error) {
        return error.getMessage() != null ? error.getMessage() : error.getClass().getSimpleName();
    }
}
