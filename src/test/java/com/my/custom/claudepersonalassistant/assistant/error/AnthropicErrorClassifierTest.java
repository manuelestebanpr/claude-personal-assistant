package com.my.custom.claudepersonalassistant.assistant.error;

import java.io.IOException;
import java.util.Optional;
import java.util.stream.Stream;

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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import com.my.custom.claudepersonalassistant.assistant.ClassifiedError;
import com.my.custom.claudepersonalassistant.assistant.ErrorClassification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.params.provider.Arguments.arguments;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

class AnthropicErrorClassifierTest {

    private final AnthropicErrorClassifier classifier = new AnthropicErrorClassifier();

    @ParameterizedTest
    @MethodSource("sdkExceptions")
    void classifiesAnthropicSdkExceptions(Throwable error, ErrorClassification expected) {
        assertThat(classifier.classify(error).classification()).isEqualTo(expected);
    }

    static Stream<Arguments> sdkExceptions() {
        return Stream.of(
                arguments(serviceException(RateLimitException.class, 429), ErrorClassification.RETRYABLE),
                arguments(serviceException(InternalServerException.class, 500), ErrorClassification.RETRYABLE),
                arguments(serviceException(InternalServerException.class, 529), ErrorClassification.RETRYABLE),
                arguments(new AnthropicIoException("connection reset", new IOException("reset")),
                        ErrorClassification.RETRYABLE),
                arguments(new AnthropicRetryableException("try again", null), ErrorClassification.RETRYABLE),
                arguments(serviceException(BadRequestException.class, 400), ErrorClassification.TERMINAL),
                arguments(serviceException(UnauthorizedException.class, 401), ErrorClassification.TERMINAL),
                arguments(serviceException(PermissionDeniedException.class, 403), ErrorClassification.TERMINAL),
                arguments(serviceException(NotFoundException.class, 404), ErrorClassification.TERMINAL),
                arguments(serviceException(UnprocessableEntityException.class, 422), ErrorClassification.TERMINAL));
    }

    @Test
    void classifiesOtherServiceExceptionsByStatusCodeRetryable() {
        AnthropicServiceException overloaded = serviceException(AnthropicServiceException.class, 529);
        given(overloaded.errorType()).willReturn(Optional.of(ErrorType.OVERLOADED_ERROR));

        ClassifiedError error = classifier.classify(overloaded);

        assertThat(error.classification()).isEqualTo(ErrorClassification.RETRYABLE);
        assertThat(error.statusCode()).isEqualTo(529);
        assertThat(error.errorType()).isEqualTo(ErrorType.OVERLOADED_ERROR.asString());
    }

    @Test
    void classifiesOtherServiceExceptionsByStatusCodeTerminal() {
        AnthropicServiceException teapot = serviceException(AnthropicServiceException.class, 418);

        ClassifiedError error = classifier.classify(teapot);

        assertThat(error.classification()).isEqualTo(ErrorClassification.TERMINAL);
        assertThat(error.statusCode()).isEqualTo(418);
    }

    @Test
    void walksTheCauseChain() {
        RateLimitException rateLimit = serviceException(RateLimitException.class, 429);
        RuntimeException wrapped = new RuntimeException("wrapper", new IllegalStateException(rateLimit));

        ClassifiedError error = classifier.classify(wrapped);

        assertThat(error.classification()).isEqualTo(ErrorClassification.RETRYABLE);
        assertThat(error.statusCode()).isEqualTo(429);
    }

    @Test
    void fallsBackToUnknownForUnrelatedErrors() {
        ClassifiedError error = classifier.classify(new IllegalStateException("boom"));

        assertThat(error.classification()).isEqualTo(ErrorClassification.UNKNOWN);
        assertThat(error.statusCode()).isNull();
        assertThat(error.errorType()).isNull();
        assertThat(error.message()).isEqualTo("boom");
    }

    @Test
    void usesExceptionTypeNameWhenMessageIsMissing() {
        ClassifiedError error = classifier.classify(new IllegalStateException());

        assertThat(error.classification()).isEqualTo(ErrorClassification.UNKNOWN);
        assertThat(error.message()).isEqualTo(IllegalStateException.class.getSimpleName());
    }

    private static <T extends AnthropicServiceException> T serviceException(Class<T> type, int statusCode) {
        // Mockito inline mock maker handles the SDK's final Kotlin exception classes.
        T exception = mock(type);
        given(exception.statusCode()).willReturn(statusCode);
        return exception;
    }
}
