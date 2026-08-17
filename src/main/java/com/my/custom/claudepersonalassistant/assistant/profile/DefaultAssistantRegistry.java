package com.my.custom.claudepersonalassistant.assistant.profile;

import java.util.List;
import java.util.Set;

import org.springframework.stereotype.Component;

import com.my.custom.claudepersonalassistant.assistant.api.AssistantRegistry;
import com.my.custom.claudepersonalassistant.assistant.config.AssistantConstants;
import com.my.custom.claudepersonalassistant.assistant.dto.AssistantDescriptor;

/**
 * The assistants this application ships, defined in code rather than properties: the test and main
 * {@code application.properties} shadow rather than merge, so a profile written into one would
 * silently not exist in the other run mode.
 *
 * <p>The tool names are string literals, not the MCP module's constants — {@code assistant} has
 * {@code allowedDependencies = {}} and importing them would be a module dependency. The exact
 * lists are pinned by {@code DefaultAssistantRegistryTest}, and the full published tool surface by
 * {@code GoogleWorkspaceToolRegistrationTests}.
 */
@Component
public class DefaultAssistantRegistry implements AssistantRegistry, AssistantProfiles {

    /**
     * Both assistants stay on Haiku deliberately: it is the model the application is configured
     * and priced for, and the receipt-import path is Haiku-gated besides.
     */
    private static final String MODEL = "claude-haiku-4-5";

    private static final AssistantProfile DEFAULT_PROFILE = new AssistantProfile(
            "default", "Personal Assistant",
            "General-purpose assistant with access to your email and the current time.",
            MODEL, AssistantConstants.SYSTEM_PROMPT,
            Set.of(),
            Set.of("get_current_hour", "gmail_create_draft", "gmail_get_message",
                    "gmail_search_messages"));

    private static final AssistantProfile GROCERIES_PROFILE = new AssistantProfile(
            "groceries", "Groceries Assistant",
            "Manages your grocery list: add, list, remove, and import photographed receipts.",
            MODEL, AssistantConstants.GROCERIES_SYSTEM_PROMPT,
            Set.of(),
            Set.of("groceries_add", "groceries_add_many", "groceries_delete",
                    "groceries_import_receipt", "groceries_list"));

    private static final List<AssistantProfile> PROFILES = List.of(DEFAULT_PROFILE, GROCERIES_PROFILE);

    @Override
    public List<AssistantDescriptor> list() {
        return PROFILES.stream().map(DefaultAssistantRegistry::toDescriptor).toList();
    }

    @Override
    public AssistantDescriptor resolve(String assistantId) {
        return toDescriptor(profile(assistantId));
    }

    @Override
    public AssistantProfile profile(String assistantId) {
        return PROFILES.stream()
                .filter(profile -> profile.id().equals(assistantId))
                .findFirst()
                .orElse(DEFAULT_PROFILE);
    }

    private static AssistantDescriptor toDescriptor(AssistantProfile profile) {
        return new AssistantDescriptor(profile.id(), profile.displayName(), profile.description());
    }
}
