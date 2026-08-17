package com.my.custom.claudepersonalassistant.chat.web;

import java.util.List;

import io.micrometer.tracing.Span;
import io.micrometer.tracing.TraceContext;
import io.micrometer.tracing.Tracer;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import com.my.custom.claudepersonalassistant.chat.api.ChatFacade;
import com.my.custom.claudepersonalassistant.chat.api.ChatTurn;
import com.my.custom.claudepersonalassistant.chat.config.ChatProperties;
import com.my.custom.claudepersonalassistant.chat.dto.StreamEvent;

import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;

/**
 * A user reporting "the answer stopped halfway" has nothing to search Tempo with unless the turn
 * hands its trace id back, so the streamed response carries one.
 *
 * <p>Kept apart from {@link ChatStreamControllerTest}, which declares no {@link Tracer} bean at all
 * and therefore covers the other half of the contract: a slice with no tracer must still answer.
 */
@WebMvcTest(ChatStreamController.class)
@EnableConfigurationProperties(ChatProperties.class)
class ChatStreamTraceHeaderTest {

    private static final String TRACE_ID = "4bf92f3577b34da6a3ce929d0e0e4736";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private ChatFacade chatFacade;

    @MockitoBean
    private Tracer tracer;

    @Test
    void answersWithTheTraceIdOfTheAmbientSpan() throws Exception {
        givenCurrentSpanWithTraceId();
        givenATurnThatCompletesImmediately();

        MvcResult result = performStream();

        mockMvc.perform(asyncDispatch(result))
                .andExpect(header().string(ChatStreamController.TRACE_ID_HEADER, TRACE_ID));
    }

    /** Tracing can be switched off entirely; a turn that cannot be labelled still has to run. */
    @Test
    void omitsTheHeaderWhenNoSpanIsCurrent() throws Exception {
        given(tracer.currentSpan()).willReturn(null);
        givenATurnThatCompletesImmediately();

        MvcResult result = performStream();

        mockMvc.perform(asyncDispatch(result))
                .andExpect(header().doesNotExist(ChatStreamController.TRACE_ID_HEADER));
    }

    private void givenCurrentSpanWithTraceId() {
        Span span = mock(Span.class);
        TraceContext traceContext = mock(TraceContext.class);
        given(tracer.currentSpan()).willReturn(span);
        given(span.context()).willReturn(traceContext);
        given(traceContext.traceId()).willReturn(TRACE_ID);
    }

    private void givenATurnThatCompletesImmediately() {
        ChatTurn turn = sink -> sink.accept(StreamEvent.done());
        given(chatFacade.prepareTurn(5L, "Hi", List.of())).willReturn(turn);
    }

    private MvcResult performStream() throws Exception {
        return mockMvc.perform(post(ChatStreamController.STREAM_PATH, 5L)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new StreamRequest("Hi", List.of()))))
                .andExpect(request().asyncStarted())
                .andReturn();
    }
}
