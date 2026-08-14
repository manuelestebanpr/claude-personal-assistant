package com.my.custom.claudepersonalassistant.assistant.config;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.my.custom.claudepersonalassistant.assistant.api.ToolExecutor;
import com.my.custom.claudepersonalassistant.assistant.config.AssistantConstants;
import com.my.custom.claudepersonalassistant.assistant.dto.ToolExecutionResult;

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

    /**
     * Backs off as soon as a real adapter (chat's {@code McpToolExecutor}) is on the classpath;
     * only keeps the module boot-able on its own — e.g. in an {@code ApplicationModuleTest}
     * scoped to just {@code assistant} — where no request ever carries tools, so this is never
     * actually invoked.
     */
    @Bean
    @ConditionalOnMissingBean(ToolExecutor.class)
    ToolExecutor noOpToolExecutor() {
        return (toolName, arguments) -> ToolExecutionResult.failed("No tool executor is configured.");
    }
}
