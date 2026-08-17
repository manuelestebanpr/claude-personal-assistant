package com.my.custom.claudepersonalassistant.mcp;

import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.modulith.test.ApplicationModuleTest;
import org.springframework.test.context.TestPropertySource;

import com.my.custom.claudepersonalassistant.mcp.domain.McpTool;
import com.my.custom.claudepersonalassistant.mcp.domain.ToolRegistry;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Boots the module with the Google tools switched on.
 *
 * <p>The clients and every tool are gated by the same condition, and nothing in the unit tests
 * would notice them disagreeing — a tool that outlived the clients would simply stop the context
 * from starting, at runtime, for want of a {@code GmailClient} bean. This is the test that fails
 * instead.
 *
 * <p>{@link McpModuleEventTests} covers the other half by omission: it boots with no Google
 * properties at all, so the whole group has to stay optional.
 */
@ApplicationModuleTest
@TestPropertySource(properties = {
        "google.workspace.enabled=true",
        "google.workspace.client-id=test-client",
        "google.workspace.client-secret=test-secret",
        "google.workspace.refresh-token=test-refresh" })
class GoogleWorkspaceToolRegistrationTests {

    @Autowired
    private ToolRegistry toolRegistry;

    /**
     * Order is asserted, not just membership: clients cache {@code tools/list} and model prompt
     * caches key on its bytes, so a list that reshuffles between restarts is a silent cost.
     *
     * <p>The whole registry, not only the Google half — a new tool group anywhere in the module
     * lands in this list, which is deliberate: this is the one place the complete published surface
     * is written down.
     */
    @Test
    void publishesEveryGoogleToolInADeterministicOrder() {
        assertThat(toolRegistry.tools()).extracting(McpTool::name).containsExactly(
                "calendar_create_event",
                "calendar_delete_event",
                "calendar_list_events",
                "calendar_update_event",
                "get_current_hour",
                "gmail_create_draft",
                "gmail_get_message",
                "gmail_search_messages",
                "groceries_add",
                "groceries_add_many",
                "groceries_delete",
                "groceries_import_receipt",
                "groceries_list");
    }

    /**
     * The drafting-only boundary is structural — there is no send call anywhere behind these tools
     * — but this is what notices if someone later adds one.
     */
    @Test
    void offersNoWayToSendMail() {
        assertThat(toolRegistry.tools()).extracting(McpTool::name)
                .noneMatch(name -> name.contains("send"));
    }

    @Test
    void describesEveryToolWellEnoughForTheModelToChooseIt() {
        assertThat(toolRegistry.tools()).allSatisfy(tool -> {
            assertThat(tool.description()).isNotBlank();
            assertThat(tool.title()).isNotBlank();
            assertThat(tool.inputSchema()).containsEntry("type", "object");
        });
    }
}
