package com.my.custom.claudepersonalassistant.mcp.domain.tool.groceries;

import java.util.List;
import java.util.Map;

import lombok.RequiredArgsConstructor;

import org.springframework.stereotype.Component;

import com.my.custom.claudepersonalassistant.mcp.domain.McpTool;
import com.my.custom.claudepersonalassistant.mcp.domain.grocery.Grocery;
import com.my.custom.claudepersonalassistant.mcp.domain.grocery.GroceryStore;

/**
 * Stores one grocery.
 *
 * <p>Kept alongside {@code groceries_add_many} rather than folded into it: a single-item call is
 * what "add milk to my groceries" actually is, and forcing every such turn through a one-element
 * array is a schema the model gets wrong more often than it gets right.
 */
@Component
@RequiredArgsConstructor
class GroceriesAddTool implements McpTool {

    static final String NAME = "groceries_add";

    private final GroceryStore store;

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String title() {
        return "Add a grocery";
    }

    @Override
    public String description() {
        return "Adds one item to the user's groceries. Call it only when the user explicitly asks "
                + "to add a grocery to the list — 'add milk, 6980, dairy' — never on your own "
                + "initiative. For several items at once use groceries_add_many instead, which "
                + "stores them in one go.";
    }

    @Override
    public Map<String, Object> inputSchema() {
        return GroceryArguments.schema().build();
    }

    @Override
    public String execute(Map<String, Object> arguments) {
        Grocery saved = store.add(GroceryArguments.toNewGrocery(arguments));
        return "Added.%n%n%s".formatted(GroceryArguments.render(List.of(saved)));
    }
}
