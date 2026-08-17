package com.my.custom.claudepersonalassistant.mcp.domain.tool.groceries;

import java.util.List;
import java.util.Map;

import lombok.RequiredArgsConstructor;

import org.springframework.stereotype.Component;

import com.my.custom.claudepersonalassistant.mcp.domain.McpTool;
import com.my.custom.claudepersonalassistant.mcp.domain.grocery.Grocery;
import com.my.custom.claudepersonalassistant.mcp.domain.grocery.GroceryStore;
import com.my.custom.claudepersonalassistant.mcp.domain.tool.ToolArguments;
import com.my.custom.claudepersonalassistant.mcp.domain.tool.ToolSchema;

/**
 * Shows what is in the groceries list.
 *
 * <p>The one tool the model should reach for before any question about what the user already has,
 * and the only way it learns the ids that {@code groceries_delete} needs.
 */
@Component
@RequiredArgsConstructor
class GroceriesListTool implements McpTool {

    static final String NAME = "groceries_list";

    static final String CATEGORY = "category";

    private final GroceryStore store;

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String title() {
        return "List groceries";
    }

    @Override
    public String description() {
        return "Lists the user's stored groceries with their ids, quantities and prices, and the "
                + "total. Call it before answering anything about what the user already has, what "
                + "they spent, or what is missing — and call it before groceries_delete, since the "
                + "ids it prints are what a deletion is addressed by.";
    }

    @Override
    public Map<String, Object> inputSchema() {
        return ToolSchema.object()
                .optional(CATEGORY, ToolSchema.string(
                        "Show only this category. Case-insensitive. Omit to list everything."))
                .build();
    }

    @Override
    public String execute(Map<String, Object> arguments) {
        String category = ToolArguments.optionalText(arguments, CATEGORY);
        List<Grocery> found = store.list(category);
        if (found.isEmpty()) {
            // A sentence, not an empty string: the latter reads to the model as a broken tool and
            // invites it to retry the same call.
            return category == null
                    ? "No groceries stored yet."
                    : "No groceries stored in category '%s'.".formatted(category);
        }
        return GroceryArguments.render(found);
    }
}
