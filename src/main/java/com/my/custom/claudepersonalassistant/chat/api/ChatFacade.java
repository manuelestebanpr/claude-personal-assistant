package com.my.custom.claudepersonalassistant.chat.api;

import java.util.List;
import java.util.Map;

import com.my.custom.claudepersonalassistant.chat.dto.AssistantDto;
import com.my.custom.claudepersonalassistant.chat.dto.ChatMessageDto;
import com.my.custom.claudepersonalassistant.chat.dto.ConversationDto;
import com.my.custom.claudepersonalassistant.chat.dto.ConversationView;
import com.my.custom.claudepersonalassistant.chat.dto.ImageUpload;
import com.my.custom.claudepersonalassistant.chat.dto.McpServerDto;
import com.my.custom.claudepersonalassistant.chat.dto.ToolDto;

/**
 * Module API of the chat module: conversation lifecycle, the streaming turn, and the tools the
 * assistant can reach.
 */
public interface ChatFacade {

    List<ConversationDto> listConversations();

    /**
     * The assistants a new conversation can be addressed to, for the home page's picker cards.
     */
    List<AssistantDto> listAssistants();

    /**
     * Creates a conversation for one assistant. The id is resolved against the assistant registry
     * before it is stored — {@code null} or an unknown id becomes the default assistant — so the
     * database only ever holds ids that mean something.
     */
    ConversationDto createConversation(String assistantId);

    /** A conversation with the default assistant. */
    default ConversationDto createConversation() {
        return createConversation(null);
    }

    ConversationView openConversation(Long chatId);

    void deleteConversation(Long chatId);

    /**
     * Validates the chat exists and persists the new user message — synchronously, so a
     * missing chat fails before the response body starts — then returns a {@link ChatTurn}
     * that streams the assistant answer, and persists it (partial on error/cancel, full on
     * completion), once driven.
     *
     * @param images sent with the message; stored with it and shown to the model this turn. Empty
     *               for an ordinary message. A message with images may have blank {@code userText}
     *               — a photograph on its own is a complete thing to say
     */
    ChatTurn prepareTurn(Long chatId, String userText, List<ImageUpload> images);

    /**
     * The MCP servers this application connects to, for the picker the composer opens on
     * {@code !} — including its own, which is just the first one configured.
     *
     * <p>Resolved here rather than in the controller so the web layer never learns what an MCP
     * server is. Never throws: an unreachable server is a row that says so.
     */
    List<McpServerDto> listServers();

    /**
     * Every tool across every reachable server, for the picker the composer opens on {@code !/}.
     *
     * <p>Returns an empty list when nothing can be reached: a tool catalogue is an enhancement, and
     * losing it must not take the chat down with it.
     */
    List<ToolDto> listTools();

    /**
     * Runs a tool on one named server and records its output as an assistant message, so a reload
     * shows what the user saw and the next turn carries the result as model context.
     *
     * @throws com.my.custom.claudepersonalassistant.chat.service.ChatNotFoundException
     *         when the conversation does not exist
     */
    ChatMessageDto executeTool(Long chatId, String serverId, String toolName, Map<String, Object> arguments);
}
