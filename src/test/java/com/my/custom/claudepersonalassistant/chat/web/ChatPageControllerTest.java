package com.my.custom.claudepersonalassistant.chat.web;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.my.custom.claudepersonalassistant.chat.api.ChatFacade;
import com.my.custom.claudepersonalassistant.chat.dto.ChatMessageDto;
import com.my.custom.claudepersonalassistant.chat.dto.ConversationDto;
import com.my.custom.claudepersonalassistant.chat.dto.ConversationView;
import com.my.custom.claudepersonalassistant.chat.dto.MessageRole;
import com.my.custom.claudepersonalassistant.chat.service.ChatNotFoundException;

import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

@WebMvcTest(ChatPageController.class)
class ChatPageControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ChatFacade chatFacade;

    @Test
    void indexRendersHomeView() throws Exception {
        mockMvc.perform(get(ChatPageController.ROOT_PATH))
                .andExpect(status().isOk())
                .andExpect(view().name(ChatPageController.HOME_VIEW));
    }

    @Test
    void chatsRootRendersConversationListWithoutCurrentChat() throws Exception {
        given(chatFacade.listConversations()).willReturn(List.of(conversation(1L, "First chat")));

        mockMvc.perform(get(ChatPageController.CHATS_PATH))
                .andExpect(status().isOk())
                .andExpect(view().name(ChatPageController.CHAT_VIEW))
                .andExpect(model().attributeExists(ChatPageController.CONVERSATIONS_ATTRIBUTE))
                .andExpect(model().attributeDoesNotExist(ChatPageController.CURRENT_CHAT_ATTRIBUTE));
    }

    @Test
    void openChatRendersCurrentChatWithMessages() throws Exception {
        ConversationDto conversation = conversation(5L, "Opened chat");
        given(chatFacade.listConversations()).willReturn(List.of(conversation));
        given(chatFacade.openConversation(5L)).willReturn(new ConversationView(conversation,
                List.of(new ChatMessageDto(1L, MessageRole.USER, "Hi", Instant.now()),
                        new ChatMessageDto(2L, MessageRole.ASSISTANT, "Hello!", Instant.now()))));

        mockMvc.perform(get(ChatPageController.CHAT_PATH, 5L))
                .andExpect(status().isOk())
                .andExpect(view().name(ChatPageController.CHAT_VIEW))
                .andExpect(model().attribute(ChatPageController.CURRENT_CHAT_ATTRIBUTE, conversation))
                .andExpect(model().attributeExists(ChatPageController.MESSAGES_ATTRIBUTE));
    }

    @Test
    void openingUnknownChatReturnsNotFound() throws Exception {
        given(chatFacade.openConversation(99L)).willThrow(new ChatNotFoundException(99L));

        mockMvc.perform(get(ChatPageController.CHAT_PATH, 99L))
                .andExpect(status().isNotFound());
    }

    @Test
    void createChatRedirectsToNewChat() throws Exception {
        given(chatFacade.createConversation()).willReturn(conversation(7L, "New chat"));

        mockMvc.perform(post(ChatPageController.CHATS_PATH))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl(ChatPageController.CHATS_PATH + "/7"));
    }

    @Test
    void deleteChatRedirectsToChatsRoot() throws Exception {
        mockMvc.perform(post(ChatPageController.DELETE_CHAT_PATH, 5L))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl(ChatPageController.CHATS_PATH));

        verify(chatFacade).deleteConversation(5L);
    }

    private ConversationDto conversation(Long id, String title) {
        return new ConversationDto(id, title);
    }
}
