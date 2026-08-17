package com.my.custom.claudepersonalassistant.mcp.domain.tool.groceries;

import java.util.List;

/**
 * How the model is asked to read a receipt.
 *
 * <p>Constants rather than properties, the same choice {@code AssistantConstants.SYSTEM_PROMPT}
 * makes: a prompt is not a per-environment knob, and this project's two {@code
 * application.properties} shadow each other rather than merging, so a prompt written into one of
 * them would take effect in one run mode and silently not in the others.
 *
 * <p>Three things here are load-bearing together and only work as a set:
 *
 * <ul>
 * <li>The <strong>worked example</strong> shows a receipt's two-line item layout — code,
 * description and total on one line, the unit breakdown wrapped onto the next. Without it the model
 * reads the wrapped line as a separate item.</li>
 * <li>The <strong>prefill</strong> ends by opening a {@code ```json} fence, so the model's first
 * generated character is already inside it.</li>
 * <li>The <strong>stop sequence</strong> closes that fence, so generation ends at the object.
 * Between the two the model can emit nothing but the JSON, and the reply needs no unwrapping before
 * it is parsed.</li>
 * </ul>
 *
 * <p>The prefill is <strong>model-gated</strong>: Anthropic rejects one with a 400 on Opus and
 * Sonnet 4.6 and later, and accepts it on Haiku 4.5, which is what
 * {@code spring.ai.anthropic.chat.model} is set to. Moving this application to a newer model breaks
 * this tool and nothing else will say so.
 *
 * <p>{@code quantity} and {@code category} are asked for even though a printed receipt shows
 * neither as a column: a stored grocery needs both, and having the model infer them while it is
 * already looking at the line is cheaper and more accurate than a second pass would be.
 */
final class ReceiptPrompt {

    static final String SYSTEM = "You read supermarket receipts from photographs and answer with "
            + "JSON only. Never explain, apologise or add commentary.";

    static final String USER = """
            Read all the receipt, from the top to the bottom. For the bought items section, produce JSON.

            The section for the products normally has a cod, descr and total, so look for that section
            in order to read and generate the rows.

            Example input on the image, for each item row

            949                       Tomate Chon              5444
                             1.085kg x 8980

            1000               Leche Alpin                  45190
                           0.789kg x 6980

            Expected output

            {"items":[
              {"code":"949","description":"Tomate Chon","price":5444,"quantity":1.085,
               "category":"frutas y verduras","other":"1.085kg x 8980"},
              {"code":"1000","description":"Leche Alpin","price":45190,"quantity":0.789,
               "category":"lacteos","other":"0.789kg x 6980"}
            ],"subtotal":50634}

            This output is good because it shows properly the code, description, price, and in "other"
            the proper separation from the name.

            "price" is the total shown for that line, with no thousands separators.
            "quantity" is the number before the unit in the "other" column, or 1 when there is none.
            "category" is the general grocery category of the item, in Spanish — lacteos, carnes,
            frutas y verduras, panaderia, bebidas, aseo, otros.
            "other" is the unit breakdown line exactly as printed, or "" when the item has none.

            Output only the JSON object with the purchased items and the subtotal, nothing else.
            """;

    /**
     * <strong>Must not end with whitespace.</strong> Anthropic rejects a prefill whose final
     * assistant content has a trailing space or newline outright — {@code 400 invalid_request_error:
     * "messages: final assistant content cannot end with trailing whitespace"} — so the newline that
     * would naturally follow the fence has to be left for the model to write. It does, and
     * {@code readTree} is given a trimmed string anyway.
     */
    static final String PREFILL = "Here is the exact json for the groceries on the receipt```json";

    static final List<String> STOP_SEQUENCES = List.of("```");

    /** A long receipt is fifty lines of JSON; the chat default of 4000 would truncate one. */
    static final int MAX_TOKENS = 8000;

    private ReceiptPrompt() {
    }
}
