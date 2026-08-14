package com.my.custom.claudepersonalassistant.mcp.api;

/**
 * One argument a tool accepts, read off its JSON Schema.
 *
 * <p>The schema is what the <em>model</em> needs; this is the same information in the shape a
 * caller needs to ask a human for it. A client that could only run zero-argument tools would leave
 * everything interesting on the server unreachable by hand.
 *
 * @param name        key to send in the arguments object
 * @param description what to put there, as written for the model
 * @param type        JSON Schema type — {@code string}, {@code integer}, {@code number},
 *                    {@code boolean} or {@code array}; defaults to {@code string} when the schema
 *                    omits it
 * @param required    whether the tool refuses to run without it
 */
public record ToolParameter(String name, String description, String type, boolean required) {
}
