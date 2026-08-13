package com.my.custom.claudepersonalassistant.chat.web;

import lombok.RequiredArgsConstructor;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;

import com.my.custom.claudepersonalassistant.chat.api.ChatFacade;
import com.my.custom.claudepersonalassistant.chat.dto.ConversationDto;
import com.my.custom.claudepersonalassistant.chat.dto.ConversationView;

/**
 * Server-rendered chat UI: sidebar with conversations, message pane, composer.
 */
@Controller
@RequiredArgsConstructor
public class ChatPageController {

    public static final String ROOT_PATH = "/";
    public static final String CHATS_PATH = "/chats";
    public static final String CHAT_PATH = "/chats/{chatId}";
    public static final String DELETE_CHAT_PATH = "/chats/{chatId}/delete";

    public static final String CHAT_VIEW = "chat";
    public static final String CONVERSATIONS_ATTRIBUTE = "conversations";
    public static final String CURRENT_CHAT_ATTRIBUTE = "currentChat";
    public static final String MESSAGES_ATTRIBUTE = "messages";

    private static final String REDIRECT = "redirect:";

    private final ChatFacade chatFacade;

    @GetMapping(ROOT_PATH)
    public String index(Model model) {
        model.addAttribute(CONVERSATIONS_ATTRIBUTE, chatFacade.listConversations());
        return CHAT_VIEW;
    }

    @GetMapping(CHAT_PATH)
    public String openChat(@PathVariable Long chatId, Model model) {
        ConversationView view = chatFacade.openConversation(chatId);
        model.addAttribute(CONVERSATIONS_ATTRIBUTE, chatFacade.listConversations());
        model.addAttribute(CURRENT_CHAT_ATTRIBUTE, view.conversation());
        model.addAttribute(MESSAGES_ATTRIBUTE, view.messages());
        return CHAT_VIEW;
    }

    @PostMapping(CHATS_PATH)
    public String createChat() {
        ConversationDto created = chatFacade.createConversation();
        return REDIRECT + CHATS_PATH + "/" + created.id();
    }

    @PostMapping(DELETE_CHAT_PATH)
    public String deleteChat(@PathVariable Long chatId) {
        chatFacade.deleteConversation(chatId);
        return REDIRECT + ROOT_PATH;
    }
}
