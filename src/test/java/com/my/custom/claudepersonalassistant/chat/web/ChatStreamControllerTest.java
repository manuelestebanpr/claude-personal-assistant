package com.my.custom.claudepersonalassistant.chat.web;

import java.util.Base64;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.ObjectMapper;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import com.my.custom.claudepersonalassistant.assistant.dto.ErrorClassification;
import com.my.custom.claudepersonalassistant.chat.api.ChatFacade;
import com.my.custom.claudepersonalassistant.chat.api.ChatTurn;
import com.my.custom.claudepersonalassistant.chat.config.ChatProperties;
import com.my.custom.claudepersonalassistant.chat.dto.ImageUpload;
import com.my.custom.claudepersonalassistant.chat.dto.StreamEvent;
import com.my.custom.claudepersonalassistant.chat.service.ChatNotFoundException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@code ChatProperties} is enabled explicitly because a {@code @WebMvcTest} slice does not load
 * {@code ChatModuleConfiguration}, and the controller reads the image caps from it. Enabling it
 * rather than mocking it also means the defaults under test are the real ones.
 */
@WebMvcTest(ChatStreamController.class)
@EnableConfigurationProperties(ChatProperties.class)
class ChatStreamControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private ChatFacade chatFacade;

    private static final String ONE_PIXEL_JPEG_BASE64 = "/9j/4AAQSkZJRg==";

    @Test
    void acceptsAnImageOnlyMessageAndDecodesTheImage() throws Exception {
        ChatTurn turn = sink -> sink.accept(StreamEvent.done());
        given(chatFacade.prepareTurn(anyLong(), any(), anyList())).willReturn(turn);

        // A photograph on its own is a complete message. The old guard rejected it for having no
        // text, which is the whole reason this endpoint could not carry an image.
        MvcResult result = mockMvc.perform(post(ChatStreamController.STREAM_PATH, 5L)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new StreamRequest("",
                                List.of(new StreamRequest.InboundImage("image/jpeg", ONE_PIXEL_JPEG_BASE64))))))
                .andExpect(request().asyncStarted())
                .andReturn();
        mockMvc.perform(asyncDispatch(result)).andExpect(status().isOk());

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<ImageUpload>> images = ArgumentCaptor.forClass(List.class);
        verify(chatFacade).prepareTurn(eq(5L), eq(""), images.capture());
        assertThat(images.getValue()).singleElement().satisfies(image -> {
            assertThat(image.mediaType()).isEqualTo("image/jpeg");
            assertThat(image.data()).isEqualTo(Base64.getDecoder().decode(ONE_PIXEL_JPEG_BASE64));
        });
    }

    @Test
    void rejectsAMessageThatIsNeitherTextNorImage() throws Exception {
        mockMvc.perform(post(ChatStreamController.STREAM_PATH, 5L)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new StreamRequest("   ", List.of()))))
                .andExpect(status().isBadRequest());
    }

    @Test
    void rejectsAMediaTypeTheModelCannotRead() throws Exception {
        // Discovered here, as a status code, rather than mid-stream — once the NDJSON body has
        // started the status is committed and an error can only be a line in the protocol.
        mockMvc.perform(post(ChatStreamController.STREAM_PATH, 5L)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new StreamRequest("look",
                                List.of(new StreamRequest.InboundImage("application/pdf", ONE_PIXEL_JPEG_BASE64))))))
                .andExpect(status().isBadRequest());
    }

    @Test
    void rejectsMoreImagesThanOneMessageMayCarry() throws Exception {
        StreamRequest.InboundImage image =
                new StreamRequest.InboundImage("image/png", ONE_PIXEL_JPEG_BASE64);

        mockMvc.perform(post(ChatStreamController.STREAM_PATH, 5L)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new StreamRequest("look",
                                List.of(image, image, image, image, image)))))
                .andExpect(status().isBadRequest());
    }

    @Test
    void rejectsSomethingThatIsNotBase64AtAll() throws Exception {
        mockMvc.perform(post(ChatStreamController.STREAM_PATH, 5L)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new StreamRequest("look",
                                List.of(new StreamRequest.InboundImage("image/jpeg", "not base64 !!!"))))))
                .andExpect(status().isBadRequest());
    }

    @Test
    void streamsNdjsonDeltasFollowedByDone() throws Exception {
        ChatTurn turn = sink -> {
            sink.accept(StreamEvent.delta("Hel"));
            sink.accept(StreamEvent.delta("lo"));
            sink.accept(StreamEvent.done());
        };
        given(chatFacade.prepareTurn(5L, "Hi", List.of())).willReturn(turn);

        MvcResult result = mockMvc.perform(post(ChatStreamController.STREAM_PATH, 5L)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new StreamRequest("Hi", List.of()))))
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
        given(chatFacade.prepareTurn(5L, "Hi", List.of())).willReturn(turn);

        MvcResult result = mockMvc.perform(post(ChatStreamController.STREAM_PATH, 5L)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new StreamRequest("Hi", List.of()))))
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
        given(chatFacade.prepareTurn(anyLong(), anyString(), anyList())).willThrow(new ChatNotFoundException(99L));

        mockMvc.perform(post(ChatStreamController.STREAM_PATH, 99L)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new StreamRequest("Hi", List.of()))))
                .andExpect(status().isNotFound());
    }

    @Test
    void blankMessageIsRejected() throws Exception {
        mockMvc.perform(post(ChatStreamController.STREAM_PATH, 5L)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new StreamRequest("   ", List.of()))))
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
