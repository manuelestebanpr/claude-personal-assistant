package com.my.custom.claudepersonalassistant.chat.web;

import java.util.List;
import java.util.Map;

import lombok.RequiredArgsConstructor;

import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import com.my.custom.claudepersonalassistant.chat.api.ChatFacade;
import com.my.custom.claudepersonalassistant.chat.dto.ChatMessageDto;
import com.my.custom.claudepersonalassistant.chat.dto.McpServerDto;
import com.my.custom.claudepersonalassistant.chat.dto.ToolDto;

/**
 * Backs the two pickers the composer opens: {@code !} for the connected MCP servers, {@code !/}
 * for the tools they expose.
 *
 * <p>Goes through {@link ChatFacade} rather than reaching for a tool gateway directly: the
 * controller's job is HTTP, and which module actually holds the connections is not its business.
 */
@RestController
@RequiredArgsConstructor
public class ToolController {

    public static final String SERVERS_PATH = "/mcp-servers";
    public static final String TOOLS_PATH = "/tools";
    public static final String EXECUTE_TOOL_PATH = "/chats/{chatId}/servers/{serverId}/tools/{toolName}";

    private final ChatFacade chatFacade;

    @GetMapping(path = SERVERS_PATH, produces = MediaType.APPLICATION_JSON_VALUE)
    public List<McpServerDto> listServers() {
        return chatFacade.listServers();
    }

    @GetMapping(path = TOOLS_PATH, produces = MediaType.APPLICATION_JSON_VALUE)
    public List<ToolDto> listTools() {
        return chatFacade.listTools();
    }

    @PostMapping(path = EXECUTE_TOOL_PATH, produces = MediaType.APPLICATION_JSON_VALUE)
    public ChatMessageDto executeTool(@PathVariable Long chatId, @PathVariable String serverId,
            @PathVariable String toolName, @RequestBody(required = false) Map<String, Object> arguments) {
        return chatFacade.executeTool(chatId, serverId, toolName, arguments == null ? Map.of() : arguments);
    }
}
