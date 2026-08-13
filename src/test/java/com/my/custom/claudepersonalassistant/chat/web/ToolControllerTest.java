package com.my.custom.claudepersonalassistant.chat.web;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.my.custom.claudepersonalassistant.chat.api.ChatFacade;
import com.my.custom.claudepersonalassistant.chat.dto.ChatMessageDto;
import com.my.custom.claudepersonalassistant.chat.dto.MessageRole;
import com.my.custom.claudepersonalassistant.chat.dto.ToolDto;
import com.my.custom.claudepersonalassistant.chat.service.ChatNotFoundException;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = ToolController.class)
class ToolControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ChatFacade chatFacade;

    @Test
    void listsTheToolsTheFacadeReports() throws Exception {
        given(chatFacade.listTools()).willReturn(List.of(
                new ToolDto("get_current_hour", "Current hour", "Returns the time.", true)));

        mockMvc.perform(get(ToolController.TOOLS_PATH))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].name").value("get_current_hour"))
                .andExpect(jsonPath("$[0].title").value("Current hour"))
                .andExpect(jsonPath("$[0].runnableAsIs").value(true));
    }

    @Test
    void executesAToolAndReturnsThePersistedMessage() throws Exception {
        given(chatFacade.executeTool(eq(7L), eq("get_current_hour"), any()))
                .willReturn(new ChatMessageDto(3L, MessageRole.ASSISTANT, "21:07", Instant.EPOCH));

        mockMvc.perform(post("/chats/7/tools/get_current_hour")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.role").value("ASSISTANT"))
                .andExpect(jsonPath("$.content").value("21:07"));
    }

    @Test
    void toleratesAnAbsentRequestBody() throws Exception {
        given(chatFacade.executeTool(anyLong(), anyString(), eq(Map.of())))
                .willReturn(new ChatMessageDto(3L, MessageRole.ASSISTANT, "21:07", Instant.EPOCH));

        mockMvc.perform(post("/chats/7/tools/get_current_hour"))
                .andExpect(status().isOk());
    }

    @Test
    void reportsAMissingChatAsNotFound() throws Exception {
        willThrow(new ChatNotFoundException(99L)).given(chatFacade).executeTool(eq(99L), anyString(), any());

        mockMvc.perform(post("/chats/99/tools/get_current_hour")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isNotFound());
    }
}
