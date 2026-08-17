package com.my.custom.claudepersonalassistant.mcp.domain.tool.groceries;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import com.my.custom.claudepersonalassistant.mcp.domain.grocery.Grocery;
import com.my.custom.claudepersonalassistant.mcp.domain.grocery.NewGrocery;
import com.my.custom.claudepersonalassistant.mcp.domain.tool.ToolArguments;
import com.my.custom.claudepersonalassistant.mcp.domain.tool.ToolSchema;

/**
 * The one shape a grocery takes on the wire, and the one way it is rendered back.
 *
 * <p>Shared by {@code groceries_add}, {@code groceries_add_many} and the receipt importer so the
 * three cannot drift: a field the bulk call accepts but the single call rejects is the kind of
 * inconsistency a model discovers the hard way, one wasted turn at a time.
 */
final class GroceryArguments {

    static final String NAME = "name";
    static final String CATEGORY = "category";
    static final String QUANTITY = "quantity";
    static final String PRICE = "price";
    static final String NOTE = "note";

    private static final BigDecimal ZERO = BigDecimal.ZERO;
    private static final BigDecimal ONE = BigDecimal.ONE;

    private GroceryArguments() {
    }

    /**
     * The per-row schema.
     *
     * <p>{@code quantity} is optional and defaults to one. Every stored grocery still has a
     * quantity — the row is never without one — but "add milk, 4000, dairy" is the common case, and
     * demanding a number the user never said would only make the model invent one.
     */
    static ToolSchema schema() {
        return ToolSchema.object()
                .required(NAME, ToolSchema.string("What the item is, as it should be listed."))
                .required(CATEGORY, ToolSchema.string(
                        "General grocery category, one or two words — for example lacteos, carnes, "
                                + "frutas y verduras, panaderia, bebidas, aseo. Matching is "
                                + "case-insensitive, so reuse an existing category rather than "
                                + "inventing a spelling of it."))
                .required(PRICE, ToolSchema.number(
                        "What was paid for this line, in the local currency, with no thousands "
                                + "separators.", 0))
                .optional(QUANTITY, ToolSchema.number(
                        "How many or how much. May be fractional for anything sold by weight. "
                                + "Defaults to 1.", 0))
                .optional(NOTE, ToolSchema.string(
                        "Anything worth keeping verbatim, such as a receipt's unit breakdown."));
    }

    static NewGrocery toNewGrocery(Map<String, Object> row) {
        return new NewGrocery(
                ToolArguments.requiredText(row, NAME),
                ToolArguments.requiredText(row, CATEGORY),
                ToolArguments.optionalDecimal(row, QUANTITY, ONE),
                ToolArguments.requiredDecimal(row, PRICE, ZERO),
                ToolArguments.optionalText(row, NOTE));
    }

    /**
     * Renders a listing the model can act on.
     *
     * <p>The id is printed on every line because it is the only handle {@code groceries_delete}
     * takes: a listing without ids can be read aloud but not acted on.
     */
    static String render(List<Grocery> groceries) {
        StringBuilder rendered = new StringBuilder("%d grocer%s:%n".formatted(
                groceries.size(), groceries.size() == 1 ? "y" : "ies"));
        BigDecimal total = ZERO;
        for (Grocery grocery : groceries) {
            total = total.add(grocery.price());
            rendered.append("%n- #%d %s (%s) — qty %s — %s".formatted(grocery.id(), grocery.name(),
                    grocery.category(), plain(grocery.quantity()), plain(grocery.price())));
            if (grocery.note() != null) {
                rendered.append(" [%s]".formatted(grocery.note()));
            }
        }
        return rendered.append("%n%nTotal %s.".formatted(plain(total))).toString();
    }

    /**
     * {@code 2.000} and {@code 45190.00} are how the database stores them and not how anyone reads
     * them. {@code toPlainString} after stripping keeps {@code 1E+2} from ever reaching the model.
     */
    static String plain(BigDecimal value) {
        return value.stripTrailingZeros().toPlainString();
    }
}
