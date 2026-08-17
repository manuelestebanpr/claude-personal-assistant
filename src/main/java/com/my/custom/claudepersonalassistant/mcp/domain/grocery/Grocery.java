package com.my.custom.claudepersonalassistant.mcp.domain.grocery;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * A stored grocery, as the tools render it back to the model.
 *
 * <p>The {@code id} is what a delete is addressed by, so it has to survive out of the store and
 * into the tool's text result — a listing the model cannot act on is only half a tool.
 */
public record Grocery(Long id, String name, String category, BigDecimal quantity, BigDecimal price,
        String note, Instant createdAt) {
}
