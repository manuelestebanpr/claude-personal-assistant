package com.my.custom.claudepersonalassistant.assistant.api;

import java.util.List;

import com.my.custom.claudepersonalassistant.assistant.dto.AssistantDescriptor;

/**
 * The assistants this module can speak as. Each one is a profile — model, system prompt and tool
 * allowlist — but only the descriptor crosses the module boundary; the profile itself is applied
 * inside {@link AssistantClient#stream} from the id a request carries.
 */
public interface AssistantRegistry {

    /** Every assistant, in the order a picker should show them. */
    List<AssistantDescriptor> list();

    /**
     * The assistant addressed by {@code assistantId}. Never throws: {@code null} or an unknown id
     * resolves to the default assistant, so a conversation created before assistants existed — or
     * one whose assistant was since removed — keeps working instead of failing every turn.
     */
    AssistantDescriptor resolve(String assistantId);
}
