package com.my.custom.claudepersonalassistant.mcp.domain.tool.groceries;

import java.util.List;
import java.util.Map;

import lombok.RequiredArgsConstructor;

import org.springframework.stereotype.Component;

import com.my.custom.claudepersonalassistant.mcp.domain.McpTool;
import com.my.custom.claudepersonalassistant.mcp.domain.grocery.Grocery;
import com.my.custom.claudepersonalassistant.mcp.domain.grocery.GroceryStore;
import com.my.custom.claudepersonalassistant.mcp.domain.grocery.NewGrocery;
import com.my.custom.claudepersonalassistant.mcp.domain.tool.ToolArguments;
import com.my.custom.claudepersonalassistant.mcp.domain.tool.ToolSchema;

/**
 * Stores a whole shopping list in one call.
 *
 * <p>Every row is converted before anything is written, so a list with one bad row is rejected
 * whole. Importing eight of nine items and reporting success is the failure nobody notices — the
 * total is simply wrong, and no error was ever shown.
 */
@Component
@RequiredArgsConstructor
class GroceriesAddManyTool implements McpTool {

    static final String NAME = "groceries_add_many";

    static final String ITEMS = "items";

    private final GroceryStore store;

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String title() {
        return "Add several groceries";
    }

    @Override
    public String description() {
        return "Adds several items to the user's groceries in one call. Use it whenever more than "
                + "one item is being recorded — a shopping list, a receipt read out loud — rather "
                + "than calling groceries_add repeatedly. Either every item is stored or none is.";
    }

    @Override
    public Map<String, Object> inputSchema() {
        return ToolSchema.object()
                .required(ITEMS, ToolSchema.objectArray("The items to store.", GroceryArguments.schema()))
                .build();
    }

    @Override
    public String execute(Map<String, Object> arguments) {
        List<NewGrocery> rows = ToolArguments.requiredObjects(arguments, ITEMS).stream()
                .map(GroceryArguments::toNewGrocery)
                .toList();
        List<Grocery> saved = store.addAll(rows);
        return "Added %d.%n%n%s".formatted(saved.size(), GroceryArguments.render(saved));
    }
}
