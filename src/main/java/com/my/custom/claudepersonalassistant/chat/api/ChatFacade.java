package com.my.custom.claudepersonalassistant.chat.api;

import java.util.List;
import java.util.Map;

import com.my.custom.claudepersonalassistant.chat.dto.ChatMessageDto;
import com.my.custom.claudepersonalassistant.chat.dto.ConversationDto;
import com.my.custom.claudepersonalassistant.chat.dto.ConversationView;
import com.my.custom.claudepersonalassistant.chat.dto.ToolDto;

/**
 * Module API of the chat module: conversation lifecycle, the streaming turn, and the tools the
 * assistant can reach.
 */
public interface ChatFacade {

    List<ConversationDto> listConversations();

    ConversationDto createConversation();

    ConversationView openConversation(Long chatId);

    void deleteConversation(Long chatId);

    /**
     * Validates the chat exists and persists the new user message — synchronously, so a
     * missing chat fails before the response body starts — then returns a {@link ChatTurn}
     * that streams the assistant answer, and persists it (partial on error/cancel, full on
     * completion), once driven.
     */
    ChatTurn prepareTurn(Long chatId, String userText);

    /**
     * Tools the assistant can reach, for the palette the composer opens on {@code /}.
     *
     * <p>Resolved here rather than in the controller so the web layer never learns that tools come
     * from an MCP server at all. Returns an empty list when that server cannot be reached: a tool
     * catalogue is an enhancement, and losing it must not take the chat down with it.
     */
    List<ToolDto> listTools();

    /**
     * Runs a tool and records its output as an assistant message, so a reload shows what the user
     * saw and the next turn carries the result as model context.
     *
     * @throws com.my.custom.claudepersonalassistant.chat.service.ChatNotFoundException
     *         when the conversation does not exist
     */
    ChatMessageDto executeTool(Long chatId, String toolName, Map<String, Object> arguments);
}
