package com.my.custom.claudepersonalassistant.assistant.profile;

import java.util.Set;

import org.junit.jupiter.api.Test;

import com.my.custom.claudepersonalassistant.assistant.config.AssistantConstants;
import com.my.custom.claudepersonalassistant.assistant.dto.AssistantDescriptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

/**
 * The two assistants this application ships, and the resolution rules around them. The tool
 * allowlists are asserted name by name: they are the entire difference between an assistant that
 * can read mail and one that can delete groceries, and a name drifting out of sync with the MCP
 * registry would fail silently at runtime — the tool would simply never be offered.
 */
class DefaultAssistantRegistryTest {

    private final DefaultAssistantRegistry registry = new DefaultAssistantRegistry();

    @Test
    void listsBothAssistantsInAStableOrder() {
        assertThat(registry.list())
                .extracting(AssistantDescriptor::id, AssistantDescriptor::displayName)
                .containsExactly(
                        tuple("default", "Personal Assistant"),
                        tuple("groceries", "Groceries Assistant"));
        assertThat(registry.list()).allSatisfy(descriptor ->
                assertThat(descriptor.description()).isNotBlank());
    }

    @Test
    void defaultAssistantKeepsTheOriginalPromptAndReachesMailAndClockOnly() {
        AssistantProfile profile = registry.profile("default");

        assertThat(profile.model()).isEqualTo("claude-haiku-4-5");
        assertThat(profile.systemPrompt()).isEqualTo(AssistantConstants.SYSTEM_PROMPT);
        assertThat(profile.allowedTools()).containsExactlyInAnyOrder(
                "get_current_hour", "gmail_create_draft", "gmail_get_message",
                "gmail_search_messages");
        assertThat(profile.allowedServers()).isEmpty();
    }

    @Test
    void groceriesAssistantReachesTheFiveGroceriesToolsOnly() {
        AssistantProfile profile = registry.profile("groceries");

        assertThat(profile.model()).isEqualTo("claude-haiku-4-5");
        assertThat(profile.systemPrompt()).isEqualTo(AssistantConstants.GROCERIES_SYSTEM_PROMPT);
        assertThat(profile.allowedTools()).containsExactlyInAnyOrder(
                "groceries_add", "groceries_add_many", "groceries_delete",
                "groceries_import_receipt", "groceries_list");
    }

    @Test
    void groceriesPromptEncodesTheDatabaseFirstAndExplicitActionRules() {
        assertThat(registry.profile("groceries").systemPrompt())
                .contains("groceries_list")
                .contains("only source of truth")
                .contains("explicitly asks")
                .contains("similar-sounding")
                .contains("groceries_import_receipt");
    }

    @Test
    void unknownOrMissingIdResolvesToTheDefaultAssistant() {
        assertThat(registry.profile(null).id()).isEqualTo("default");
        assertThat(registry.profile("nope").id()).isEqualTo("default");
        assertThat(registry.resolve(null).id()).isEqualTo("default");
        assertThat(registry.resolve("groceries").id()).isEqualTo("groceries");
    }

    @Test
    void emptyAllowlistsMeanEverythingAndServerFilteringFailsClosedOnUnknownOrigin() {
        AssistantProfile open = new AssistantProfile("x", "X", "", "m", "p", Set.of(), Set.of());
        assertThat(open.allowsTool("anywhere", "any_tool")).isTrue();
        assertThat(open.allowsTool(null, "any_tool")).isTrue();

        AssistantProfile scoped = new AssistantProfile("y", "Y", "", "m", "p",
                Set.of("local"), Set.of("tool_a"));
        assertThat(scoped.allowsTool("local", "tool_a")).isTrue();
        assertThat(scoped.allowsTool("remote", "tool_a")).isFalse();
        assertThat(scoped.allowsTool(null, "tool_a")).isFalse();
        assertThat(scoped.allowsTool("local", "tool_b")).isFalse();
    }
}
