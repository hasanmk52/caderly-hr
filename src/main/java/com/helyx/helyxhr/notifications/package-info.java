/**
 * Outbound notifications. {@link com.helyx.helyxhr.notifications.EmailOutboxService} is the only
 * supported way to send email (CLAUDE.md §6a); the {@code system} sub-package holds the durable
 * queue and the dispatcher that drains it.
 */
@NullMarked
package com.helyx.helyxhr.notifications;

import org.jspecify.annotations.NullMarked;
