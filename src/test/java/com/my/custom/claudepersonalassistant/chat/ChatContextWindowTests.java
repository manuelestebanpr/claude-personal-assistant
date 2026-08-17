package com.my.custom.claudepersonalassistant.chat;

import java.util.List;

import java.util.function.Consumer;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.modulith.test.ApplicationModuleTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import com.my.custom.claudepersonalassistant.assistant.api.AssistantClient;
import com.my.custom.claudepersonalassistant.assistant.api.AssistantRegistry;
import com.my.custom.claudepersonalassistant.assistant.api.VisionClient;
import com.my.custom.claudepersonalassistant.assistant.dto.AssistantDescriptor;
import com.my.custom.claudepersonalassistant.assistant.dto.AssistantRequest;
import com.my.custom.claudepersonalassistant.assistant.dto.HistoryMessage;
import com.my.custom.claudepersonalassistant.assistant.dto.HistoryRole;
import com.my.custom.claudepersonalassistant.chat.api.ChatFacade;
import com.my.custom.claudepersonalassistant.chat.dto.ConversationDto;
import com.my.custom.claudepersonalassistant.mcp.api.McpToolGateway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willAnswer;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

@ApplicationModuleTest
@TestPropertySource(properties = "chat.context-window-size=2")
class ChatContextWindowTests {

    @MockitoBean
    private AssistantClient assistantClient;

    /** The chat module boots alone here, so every assistant port it depends on has to be stood in for. */
    @MockitoBean
    private VisionClient visionClient;

    @MockitoBean
    private McpToolGateway toolGateway;

    @MockitoBean
    private AssistantRegistry assistantRegistry;

    @Autowired
    private ChatFacade chatFacade;

    @BeforeEach
    void resolveEveryAssistantToTheDefault() {
        given(assistantRegistry.resolve(any()))
                .willReturn(new AssistantDescriptor("default", "Personal Assistant", ""));
    }

    @Test
    void replaysOnlyTheConfiguredNumberOfTrailingMessages() {
        willAnswer(invocation -> {
            Consumer<String> onDelta = invocation.getArgument(1);
            onDelta.accept("ok");
            return null;
        }).given(assistantClient).stream(any(), any());
        ConversationDto chat = chatFacade.createConversation();

        chatFacade.prepareTurn(chat.id(), "m1", List.of()).stream(event -> { });
        chatFacade.prepareTurn(chat.id(), "m2", List.of()).stream(event -> { });
        chatFacade.prepareTurn(chat.id(), "m3", List.of()).stream(event -> { });

        ArgumentCaptor<AssistantRequest> requests = ArgumentCaptor.forClass(AssistantRequest.class);
        verify(assistantClient, times(3)).stream(requests.capture(), any());
        // Full history before the third turn is [USER m1, ASSISTANT ok, USER m2, ASSISTANT ok];
        // a window of 2 keeps only the trailing pair, oldest first.
        assertThat(requests.getAllValues().getLast().history()).containsExactly(
                new HistoryMessage(HistoryRole.USER, "m2"),
                new HistoryMessage(HistoryRole.ASSISTANT, "ok"));
    }
}
