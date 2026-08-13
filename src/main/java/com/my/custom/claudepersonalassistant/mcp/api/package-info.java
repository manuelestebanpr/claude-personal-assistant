/**
 * The outbound port for reaching an MCP server: discovering tools and invoking one.
 *
 * <p>Nothing about JSON-RPC, HTTP or the MCP wire format crosses this boundary, so the transport
 * can move from the loopback endpoint to a remote server without callers noticing.
 */
@NamedInterface
package com.my.custom.claudepersonalassistant.mcp.api;

import org.springframework.modulith.NamedInterface;
