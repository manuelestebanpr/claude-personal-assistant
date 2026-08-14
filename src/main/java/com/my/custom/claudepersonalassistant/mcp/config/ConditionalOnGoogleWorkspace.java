package com.my.custom.claudepersonalassistant.mcp.config;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

/**
 * Registers a bean only when the Google Workspace tools are switched on.
 *
 * <p>A meta-annotation rather than the raw condition repeated on each tool: the clients and every
 * tool that uses them have to agree on one switch, and one of them silently disagreeing would stop
 * the whole context from starting for want of a {@code GmailClient} bean.
 */
@Target({ ElementType.TYPE, ElementType.METHOD })
@Retention(RetentionPolicy.RUNTIME)
@Documented
@ConditionalOnProperty(prefix = "google.workspace", name = "enabled", havingValue = "true")
public @interface ConditionalOnGoogleWorkspace {
}
