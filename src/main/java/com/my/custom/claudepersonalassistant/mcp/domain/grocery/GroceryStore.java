package com.my.custom.claudepersonalassistant.mcp.domain.grocery;

import java.util.List;

/**
 * The groceries the assistant keeps track of.
 *
 * <p>Lives in {@code mcp} rather than in a module of its own: the tools are the only way in, and a
 * second module would buy nothing but a boundary to cross. The cost, stated plainly, is that
 * {@code mcp} is no longer a pure protocol module — a future groceries page in {@code chat} cannot
 * read this directly and would need a port through {@code mcp::api}.
 */
public interface GroceryStore {

    Grocery add(NewGrocery grocery);

    List<Grocery> addAll(List<NewGrocery> groceries);

    /**
     * @param category matched case- and whitespace-insensitively; {@code null} or blank returns
     *                 everything
     */
    List<Grocery> list(String category);

    /**
     * @return how many rows actually went away — an id that was already gone is not an error, since
     *         the caller may be working from a listing that has since moved on
     */
    int delete(List<Long> ids);
}
