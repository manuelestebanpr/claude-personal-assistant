package com.my.custom.claudepersonalassistant.chat.web;

import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

import io.micrometer.tracing.Span;
import io.micrometer.tracing.TraceContext;
import io.micrometer.tracing.Tracer;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import tools.jackson.databind.ObjectMapper;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import com.my.custom.claudepersonalassistant.chat.api.ChatFacade;
import com.my.custom.claudepersonalassistant.chat.api.ChatTurn;
import com.my.custom.claudepersonalassistant.chat.config.ChatProperties;
import com.my.custom.claudepersonalassistant.chat.dto.ImageUpload;
import com.my.custom.claudepersonalassistant.chat.dto.StreamEvent;

/**
 * Streams assistant answers as NDJSON over the servlet response, one line per {@link StreamEvent},
 * pushed to the network as it is produced. Blocking here is cheap on virtual threads
 * ({@code spring.threads.virtual.enabled=true}).
 *
 * <p><strong>The per-line flush must go through {@link HttpServletResponse#flushBuffer()}, not
 * through the {@code OutputStream} handed to the callback.</strong> Since Spring Framework 7.0.6,
 * {@code ServletServerHttpResponse} wraps the servlet output stream in
 * {@code StreamUtils$NonFlushingOutputStream} — whose {@code flush()} is an empty method — unless
 * the {@code spring.http.response.flush.enabled} system flag is set. Calling {@code flush()} on
 * that wrapper therefore does nothing at all: every line stays in Tomcat's 8 KB response buffer
 * until {@code StreamingResponseBodyReturnValueHandler} performs its single real flush <em>after</em>
 * this callback has fully returned, and the browser receives the whole answer in one chunk once
 * generation is already finished. Measured at the socket: five NDJSON lines arrived as one 0x9b
 * chunk at t=1209ms; with the flush below, one chunk per line at t=307/607/908/1208ms.
 *
 * <p>{@code flushBuffer()} is used rather than the global system flag because the flag is read by
 * {@code SpringProperties} (a classpath {@code spring.properties} resource plus system properties)
 * and never from the Spring {@code Environment} — so it cannot be set from
 * {@code application.properties}, and setting it from {@code main} would not apply to
 * {@code @SpringBootTest}, which never calls {@code main}. Flushing the response directly behaves
 * identically in every run mode and does not re-enable flushing for the rest of the application.
 *
 * <p>The response also carries {@value #TRACE_ID_HEADER}: once the status is committed a stalled or
 * truncated stream leaves nothing to search Tempo with, and a header is the one place to put that id
 * without touching the NDJSON protocol — the browser client never reads it, so no {@code StreamEvent}
 * type had to be invented for it.
 */
@RestController
@RequiredArgsConstructor
public class ChatStreamController {

    public static final String STREAM_PATH = "/chats/{chatId}/messages/stream";
    public static final String TRACE_ID_HEADER = "X-Trace-Id";

    private static final Set<String> SUPPORTED_IMAGE_TYPES =
            Set.of("image/jpeg", "image/png", "image/gif", "image/webp");

    private final ChatFacade chatFacade;
    private final ObjectMapper objectMapper;
    private final ChatProperties properties;

    /**
     * Optional on purpose: a {@code @WebMvcTest} slice, and any run with tracing switched off, has
     * no {@link Tracer} bean, and a turn that cannot be labelled must still stream.
     */
    private final ObjectProvider<Tracer> tracer;

    @PostMapping(path = STREAM_PATH,
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_NDJSON_VALUE)
    public ResponseEntity<StreamingResponseBody> stream(@PathVariable Long chatId,
            @RequestBody StreamRequest request, HttpServletResponse servletResponse) {
        if (request == null) {
            return ResponseEntity.badRequest().build();
        }
        List<ImageUpload> images;
        try {
            images = toImageUploads(request.images());
        }
        catch (IllegalArgumentException rejected) {
            return ResponseEntity.badRequest().build();
        }
        // Blank text is only empty when nothing came with it: a photograph on its own is a complete
        // message, and the model is given a note naming it either way.
        if (!StringUtils.hasText(request.content()) && images.isEmpty()) {
            return ResponseEntity.badRequest().build();
        }
        ChatTurn turn = chatFacade.prepareTurn(chatId, request.content(), images);
        StreamingResponseBody body =
                outputStream -> turn.stream(event -> writeLine(outputStream, servletResponse, event));
        ResponseEntity.BodyBuilder response = ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_NDJSON);
        currentTraceId().ifPresent(traceId -> response.header(TRACE_ID_HEADER, traceId));
        return response.body(body);
    }

    /**
     * Decodes and vets the uploaded images before a single row is written.
     *
     * <p>Rejecting here rather than deeper is what keeps a bad upload from costing a half-written
     * turn: {@code prepareTurn} persists the user message the moment it is called, so an image that
     * fails validation afterwards would leave a message in the transcript with nothing attached.
     *
     * <p>Validation is hand-written because this project carries no bean-validation starter and the
     * pom is frozen — the same reason {@code McpRequestValidator} exists. All four checks matter:
     * the count and size bounds cap what one request can cost, and the media type is checked against
     * what Anthropic actually accepts, since an unsupported one is only discovered mid-stream, after
     * the status is already committed and the error can no longer be a status code.
     */
    private List<ImageUpload> toImageUploads(List<StreamRequest.InboundImage> images) {
        if (images == null || images.isEmpty()) {
            return List.of();
        }
        if (images.size() > properties.maxImagesPerMessage()) {
            throw new IllegalArgumentException("too many images");
        }
        return images.stream().map(this::toImageUpload).toList();
    }

    private ImageUpload toImageUpload(StreamRequest.InboundImage image) {
        if (image == null || image.mediaType() == null
                || !SUPPORTED_IMAGE_TYPES.contains(image.mediaType().toLowerCase(Locale.ROOT))) {
            throw new IllegalArgumentException("unsupported media type");
        }
        // Throws IllegalArgumentException on anything that is not base64, which is the same signal
        // every other rejection here raises.
        byte[] data = Base64.getDecoder().decode(image.dataBase64() == null ? "" : image.dataBase64());
        if (data.length == 0 || data.length > properties.maxImageBytes()) {
            throw new IllegalArgumentException("image size out of bounds");
        }
        return new ImageUpload(image.mediaType().toLowerCase(Locale.ROOT), data);
    }

    /** The ambient span's trace id, or empty when nothing is tracing this request. */
    private Optional<String> currentTraceId() {
        return Optional.ofNullable(tracer.getIfAvailable())
                .map(Tracer::currentSpan)
                .map(Span::context)
                .map(TraceContext::traceId);
    }

    /**
     * Writes one NDJSON line and pushes it to the network. An {@link IOException} here means the
     * client is gone; it is rethrown so {@code DefaultChatFacade.streamAnswer} can cancel the
     * upstream model call and persist the partial answer.
     */
    private void writeLine(OutputStream outputStream, HttpServletResponse servletResponse, StreamEvent event) {
        try {
            outputStream.write(objectMapper.writeValueAsBytes(event));
            outputStream.write('\n');
            servletResponse.flushBuffer();
        } catch (IOException clientGone) {
            throw new UncheckedIOException(clientGone);
        }
    }
}
