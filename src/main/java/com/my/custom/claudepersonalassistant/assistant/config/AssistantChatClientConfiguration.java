package com.my.custom.claudepersonalassistant.assistant.config;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.my.custom.claudepersonalassistant.assistant.AssistantConstants;

/**
 * Builds the module's {@link ChatClient} from the auto-configured (prototype)
 * {@link ChatClient.Builder}, installing the shared system prompt.
 */
@Configuration(proxyBeanMethods = false)
class AssistantChatClientConfiguration {

    @Bean
    ChatClient assistantChatClient(ChatClient.Builder chatClientBuilder) {
        return chatClientBuilder
                .defaultSystem(AssistantConstants.SYSTEM_PROMPT)
                .build();
    }
}
