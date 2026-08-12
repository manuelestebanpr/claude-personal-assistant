package com.my.custom.claudepersonalassistant.chat.web;

import java.util.List;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import com.my.custom.claudepersonalassistant.assistant.ErrorClassification;
import com.my.custom.claudepersonalassistant.chat.ChatFacade;
import com.my.custom.claudepersonalassistant.chat.ChatTurn;
import com.my.custom.claudepersonalassistant.chat.StreamEvent;
import com.my.custom.claudepersonalassistant.chat.service.ChatNotFoundException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(ChatStreamController.class)
class ChatStreamControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private ChatFacade chatFacade;

    @Test
    void streamsNdjsonDeltasFollowedByDone() throws Exception {
        ChatTurn turn = sink -> {
            sink.accept(StreamEvent.delta("Hel"));
            sink.accept(StreamEvent.delta("lo"));
            sink.accept(StreamEvent.done());
        };
        given(chatFacade.prepareTurn(5L, "Hi")).willReturn(turn);

        MvcResult result = mockMvc.perform(post(ChatStreamController.STREAM_PATH, 5L)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new StreamRequest("Hi"))))
                .andExpect(request().asyncStarted())
                .andReturn();

        mockMvc.perform(asyncDispatch(result))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_NDJSON));

        List<StreamEvent> events = parseLines(result.getResponse().getContentAsString());
        assertThat(events).extracting(StreamEvent::type).containsExactly(
                StreamEvent.Type.DELTA, StreamEvent.Type.DELTA, StreamEvent.Type.DONE);
        assertThat(events).extracting(StreamEvent::content).containsExactly("Hel", "lo", null);
    }

    @Test
    void emitsTerminalErrorLineWhenTheStreamFails() throws Exception {
        ChatTurn turn = sink -> {
            sink.accept(StreamEvent.delta("partial"));
            sink.accept(StreamEvent.error(ErrorClassification.RETRYABLE, "rate limited"));
        };
        given(chatFacade.prepareTurn(5L, "Hi")).willReturn(turn);

        MvcResult result = mockMvc.perform(post(ChatStreamController.STREAM_PATH, 5L)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new StreamRequest("Hi"))))
                .andExpect(request().asyncStarted())
                .andReturn();

        mockMvc.perform(asyncDispatch(result)).andExpect(status().isOk());

        List<StreamEvent> events = parseLines(result.getResponse().getContentAsString());
        assertThat(events).extracting(StreamEvent::type)
                .containsExactly(StreamEvent.Type.DELTA, StreamEvent.Type.ERROR);
        StreamEvent error = events.getLast();
        assertThat(error.classification()).isEqualTo(ErrorClassification.RETRYABLE);
        assertThat(error.message()).isEqualTo("rate limited");
    }

    @Test
    void unknownChatFailsBeforeTheStreamStarts() throws Exception {
        given(chatFacade.prepareTurn(anyLong(), anyString())).willThrow(new ChatNotFoundException(99L));

        mockMvc.perform(post(ChatStreamController.STREAM_PATH, 99L)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new StreamRequest("Hi"))))
                .andExpect(status().isNotFound());
    }

    @Test
    void blankMessageIsRejected() throws Exception {
        mockMvc.perform(post(ChatStreamController.STREAM_PATH, 5L)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new StreamRequest("   "))))
                .andExpect(status().isBadRequest());
    }

    private List<StreamEvent> parseLines(String body) {
        return body.lines()
                .filter(line -> !line.isBlank())
                .map(this::parseLine)
                .toList();
    }

    private StreamEvent parseLine(String line) {
        try {
            return objectMapper.readValue(line, StreamEvent.class);
        } catch (Exception e) {
            throw new IllegalStateException("Unparseable NDJSON line: " + line, e);
        }
    }
}
