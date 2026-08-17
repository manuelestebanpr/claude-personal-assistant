package com.my.custom.claudepersonalassistant.mcp.domain.tool.groceries;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import lombok.RequiredArgsConstructor;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import com.my.custom.claudepersonalassistant.mcp.api.ImageAnalysis;
import com.my.custom.claudepersonalassistant.mcp.api.ImageAnalysisException;
import com.my.custom.claudepersonalassistant.mcp.api.ImageAnalysisRequest;
import com.my.custom.claudepersonalassistant.mcp.domain.McpTool;
import com.my.custom.claudepersonalassistant.mcp.domain.ToolExecutionException;
import com.my.custom.claudepersonalassistant.mcp.domain.grocery.Grocery;
import com.my.custom.claudepersonalassistant.mcp.domain.grocery.GroceryStore;
import com.my.custom.claudepersonalassistant.mcp.domain.grocery.NewGrocery;
import com.my.custom.claudepersonalassistant.mcp.domain.tool.ToolArguments;
import com.my.custom.claudepersonalassistant.mcp.domain.tool.ToolSchema;

/**
 * Reads a photographed receipt and stores what was bought.
 *
 * <p>The one tool here that calls the model itself, through the {@link ImageAnalysis} inverse port —
 * see that interface for why a module with {@code allowedDependencies = {}} can do this at all.
 * It is a second, nested model call inside whatever turn invoked it, so it is slow by nature; the
 * five-minute {@code spring.mvc.async.request-timeout} is what makes that survivable.
 *
 * <p>Nothing about the reply is taken on trust. A model asked for JSON will occasionally answer in
 * prose, and a receipt is exactly the kind of input where a misread line is invisible afterwards —
 * so a reply that will not parse, a receipt with no items, and a row missing a field all fail the
 * whole call rather than storing part of it. The printed subtotal is checked against the rows and
 * reported when it disagrees, which is the only cheap way to catch a price the model misread.
 */
@Component
@RequiredArgsConstructor
class GroceriesImportReceiptTool implements McpTool {

    static final String NAME = "groceries_import_receipt";

    static final String IMAGE_ID = "image_id";

    private static final String ITEMS = "items";
    private static final String SUBTOTAL = "subtotal";
    private static final String CODE = "code";
    private static final String DESCRIPTION = "description";
    private static final String OTHER = "other";
    private static final int ECHO_LIMIT = 400;

    private final GroceryStore store;

    /**
     * Optional because {@code mcp} boots on its own in its own module test, where nothing implements
     * the port. Registering and explaining itself when called beats refusing to start and taking
     * every other tool down with it.
     */
    private final ObjectProvider<ImageAnalysis> imageAnalysis;

    private final ObjectMapper objectMapper;

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String title() {
        return "Import groceries from a receipt photo";
    }

    @Override
    public String description() {
        return "Reads a photographed supermarket receipt and stores every purchased item in the "
                + "user's groceries, with its price, quantity and a category. Call it when the "
                + "user asks to add a photographed receipt to the groceries list. Pass the id of "
                + "an image the user attached to a message — the ids are noted on the message that "
                + "carried them. Use this instead of transcribing the receipt yourself: it reads "
                + "the photo directly and stores the rows in one step.";
    }

    @Override
    public Map<String, Object> inputSchema() {
        return ToolSchema.object()
                .required(IMAGE_ID, ToolSchema.integer(
                        "Id of the attached receipt photo, as noted on the message that carried it.",
                        1, Integer.MAX_VALUE))
                .build();
    }

    @Override
    public String execute(Map<String, Object> arguments) {
        long imageId = ToolArguments.requiredIdentifiers(arguments, IMAGE_ID).getFirst();
        String reply = read(imageId);
        JsonNode receipt = parse(reply);
        List<NewGrocery> rows = toRows(receipt.get(ITEMS));
        List<Grocery> saved = store.addAll(rows);
        return render(saved, receipt.get(SUBTOTAL));
    }

    private String read(long imageId) {
        ImageAnalysis analysis = imageAnalysis.getIfAvailable();
        if (analysis == null) {
            throw new ToolExecutionException(
                    "Reading images is not available in this configuration, so a receipt cannot be "
                            + "imported. Ask the user to type the items instead, or use "
                            + "groceries_add_many.");
        }
        try {
            return analysis.analyze(new ImageAnalysisRequest(imageId, ReceiptPrompt.SYSTEM,
                    ReceiptPrompt.USER, ReceiptPrompt.PREFILL, ReceiptPrompt.STOP_SEQUENCES,
                    ReceiptPrompt.MAX_TOKENS));
        }
        catch (ImageAnalysisException unreadable) {
            throw new ToolExecutionException(unreadable.getMessage(), unreadable);
        }
    }

    private JsonNode parse(String reply) {
        JsonNode receipt;
        try {
            receipt = objectMapper.readTree(reply == null ? "" : reply.trim());
        }
        catch (JacksonException notJson) {
            // Echoed back rather than swallowed: the model wrote this, and seeing its own output
            // quoted is what lets it work out that it answered in prose and try again.
            throw new ToolExecutionException(
                    "The receipt could not be read as JSON. The reply was: %s".formatted(echo(reply)));
        }
        if (receipt == null || !receipt.has(ITEMS) || !receipt.get(ITEMS).isArray()) {
            throw new ToolExecutionException(
                    "The receipt reply had no items array. The reply was: %s".formatted(echo(reply)));
        }
        if (receipt.get(ITEMS).isEmpty()) {
            throw new ToolExecutionException("The receipt was read but contained no items. Check the "
                    + "photo shows the products section, and try again.");
        }
        return receipt;
    }

    /**
     * Converts every row before storing any of them, so a receipt with one unreadable line is
     * refused whole. Importing nine of ten items and reporting success is the failure that only
     * shows up as a total that does not add up.
     */
    private List<NewGrocery> toRows(JsonNode items) {
        return items.valueStream().map(this::toRow).toList();
    }

    private NewGrocery toRow(JsonNode item) {
        Map<String, Object> row = objectMapper.convertValue(item, Map.class);
        String name = ToolArguments.requiredText(row, DESCRIPTION);
        BigDecimal price = ToolArguments.requiredDecimal(row, GroceryArguments.PRICE, BigDecimal.ZERO);
        BigDecimal quantity = ToolArguments.optionalDecimal(row, GroceryArguments.QUANTITY, BigDecimal.ONE);
        String category = StringUtils.hasText(ToolArguments.optionalText(row, GroceryArguments.CATEGORY))
                ? ToolArguments.optionalText(row, GroceryArguments.CATEGORY)
                : "otros";
        return new NewGrocery(name, category, quantity, price, note(row));
    }

    /** The code and the unit breakdown are how a stored row is traced back to the paper receipt. */
    private String note(Map<String, Object> row) {
        String code = ToolArguments.optionalText(row, CODE);
        String other = ToolArguments.optionalText(row, OTHER);
        StringBuilder note = new StringBuilder();
        if (StringUtils.hasText(code)) {
            note.append("cod ").append(code);
        }
        if (StringUtils.hasText(other)) {
            note.append(note.isEmpty() ? "" : " — ").append(other);
        }
        return note.isEmpty() ? null : note.toString();
    }

    private String render(List<Grocery> saved, JsonNode subtotal) {
        StringBuilder rendered = new StringBuilder(
                "Imported %d item(s) from the receipt.%n%n%s".formatted(saved.size(),
                        GroceryArguments.render(saved)));
        if (subtotal != null && subtotal.isNumber()) {
            BigDecimal printed = subtotal.decimalValue();
            BigDecimal counted = saved.stream().map(Grocery::price)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            if (printed.compareTo(counted) != 0) {
                // Stored anyway — the rows are still worth having — but said out loud, because a
                // silent mismatch is a misread price nobody catches.
                rendered.append("%n%nThe receipt's printed subtotal is %s, which does not match the "
                        .formatted(GroceryArguments.plain(printed)))
                        .append("%s counted from these rows. One of the lines was probably misread — "
                                .formatted(GroceryArguments.plain(counted)))
                        .append("check the photo against the list above.");
            }
        }
        return rendered.toString();
    }

    private String echo(String reply) {
        if (!StringUtils.hasText(reply)) {
            return "(empty)";
        }
        String trimmed = reply.trim();
        return trimmed.length() <= ECHO_LIMIT ? trimmed : trimmed.substring(0, ECHO_LIMIT) + "…";
    }
}
