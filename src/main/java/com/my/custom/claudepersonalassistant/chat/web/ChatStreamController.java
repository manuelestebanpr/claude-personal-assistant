package com.my.custom.claudepersonalassistant.chat.web;

import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;

import lombok.RequiredArgsConstructor;
import tools.jackson.databind.ObjectMapper;

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
import com.my.custom.claudepersonalassistant.chat.dto.StreamEvent;

/**
 * Streams assistant answers as NDJSON over the servlet OutputStream. The chat module writes
 * each event straight into this stream as it is produced; blocking here is cheap on virtual
 * threads ({@code spring.threads.virtual.enabled=true}).
 */
@RestController
@RequiredArgsConstructor
public class ChatStreamController {

    public static final String STREAM_PATH = "/chats/{chatId}/messages/stream";

    private final ChatFacade chatFacade;
    private final ObjectMapper objectMapper;

    @PostMapping(path = STREAM_PATH,
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_NDJSON_VALUE)
    public ResponseEntity<StreamingResponseBody> stream(@PathVariable Long chatId,
            @RequestBody StreamRequest request) {
        if (request == null || !StringUtils.hasText(request.content())) {
            return ResponseEntity.badRequest().build();
        }
        ChatTurn turn = chatFacade.prepareTurn(chatId, request.content());
        StreamingResponseBody body = outputStream -> turn.stream(event -> writeLine(outputStream, event));
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_NDJSON)
                .body(body);
    }

    private void writeLine(OutputStream outputStream, StreamEvent event) {
        try {
            outputStream.write(objectMapper.writeValueAsBytes(event));
            outputStream.write('\n');
            outputStream.flush();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
