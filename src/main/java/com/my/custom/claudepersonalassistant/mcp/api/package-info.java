/**
 * This module's ports, in both directions.
 *
 * <p><strong>Outbound</strong> — {@link com.my.custom.claudepersonalassistant.mcp.api.McpToolGateway}
 * for reaching an MCP server: discovering tools and invoking one. Nothing about JSON-RPC, HTTP or
 * the MCP wire format crosses this boundary, so the transport can move from the loopback endpoint to
 * a remote server without callers noticing.
 *
 * <p><strong>Inverse</strong> — {@link com.my.custom.claudepersonalassistant.mcp.api.ImageAnalysis},
 * which this module <em>declares</em> and something above implements. A tool that needs to read an
 * image has to reach both the module that stores images and the module that talks to the model, and
 * {@code allowedDependencies = {}} forbids both; stating the need here is how it gets met without
 * a dependency. Same shape as {@code assistant::api.ToolExecutor} in the other direction.
 */
@NamedInterface
package com.my.custom.claudepersonalassistant.mcp.api;

import org.springframework.modulith.NamedInterface;
