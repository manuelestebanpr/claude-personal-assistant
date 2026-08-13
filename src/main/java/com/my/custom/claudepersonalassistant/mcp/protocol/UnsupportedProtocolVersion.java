package com.my.custom.claudepersonalassistant.mcp.protocol;

import java.util.List;

/**
 * The {@code data} member returned when a client asks for a protocol revision this server does not
 * implement, so it can retry against a version both sides know.
 */
public record UnsupportedProtocolVersion(String requested, List<String> supported) {
}
