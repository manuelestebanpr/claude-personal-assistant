package com.my.custom.claudepersonalassistant.mcp.domain.grocery;

import java.math.BigDecimal;

/**
 * A grocery about to be stored: everything a row needs except what the database assigns.
 *
 * <p>Separate from {@link Grocery} so a caller cannot invent an id or a timestamp — the two look
 * alike today, and the moment they share a type someone passes a {@code Grocery} back in and the
 * store has to decide whether that means insert or update.
 *
 * @param quantity how many, or how much — decimal because a receipt sells 1.085 kg of tomatoes
 * @param price    what was paid for this line, in the store's own currency; no conversion happens
 *                 anywhere
 * @param note     free text kept verbatim from wherever the row came from, typically a receipt's
 *                 unit breakdown ({@code 1.085kg x 8980}). Nullable.
 */
public record NewGrocery(String name, String category, BigDecimal quantity, BigDecimal price, String note) {
}
