package com.my.custom.claudepersonalassistant.mcp.domain.tool.groceries;

import java.util.List;
import java.util.Map;

import lombok.RequiredArgsConstructor;

import org.springframework.stereotype.Component;

import com.my.custom.claudepersonalassistant.mcp.domain.McpTool;
import com.my.custom.claudepersonalassistant.mcp.domain.grocery.GroceryStore;
import com.my.custom.claudepersonalassistant.mcp.domain.tool.ToolArguments;
import com.my.custom.claudepersonalassistant.mcp.domain.tool.ToolSchema;

/**
 * Removes groceries by id.
 *
 * <p>The destructive tool of the group, and its schema is the guard: ids are the only argument, so
 * there is no category or name pattern to get wrong and the model must have listed the rows before
 * it can name them. A bare id is accepted where a list is declared, since "delete number seven" is
 * one call.
 */
@Component
@RequiredArgsConstructor
class GroceriesDeleteTool implements McpTool {

    static final String NAME = "groceries_delete";

    static final String IDS = "ids";

    private final GroceryStore store;

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String title() {
        return "Delete groceries";
    }

    @Override
    public String description() {
        return "Deletes groceries by id, permanently. Call groceries_list first to find the ids — "
                + "there is no way to delete by name, on purpose. Pass every id to remove in one "
                + "call. Ids that no longer exist are skipped rather than failing the call.";
    }

    @Override
    public Map<String, Object> inputSchema() {
        return ToolSchema.object()
                .required(IDS, ToolSchema.integerArray(
                        "The ids to delete, as printed by groceries_list."))
                .build();
    }

    @Override
    public String execute(Map<String, Object> arguments) {
        List<Long> ids = ToolArguments.requiredIdentifiers(arguments, IDS);
        int deleted = store.delete(ids);
        if (deleted == 0) {
            return "No groceries matched those ids — nothing was deleted. List them again to see "
                    + "what is actually stored.";
        }
        return "Deleted %d of the %d id(s) given.".formatted(deleted, ids.size());
    }
}
