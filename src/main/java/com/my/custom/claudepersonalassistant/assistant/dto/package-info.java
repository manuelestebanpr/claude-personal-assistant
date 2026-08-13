/**
 * Value types crossing the assistant boundary: the request, the history replayed as
 * model context, and the classification of a failure.
 *
 * <p>Exposed as a named interface, so other modules may depend on this package by name
 * while everything outside the declared interfaces stays module-internal.
 */
@NamedInterface
package com.my.custom.claudepersonalassistant.assistant.dto;

import org.springframework.modulith.NamedInterface;
