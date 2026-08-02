package com.helyx.helyxhr.notifications;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Enables the scheduler the outbox dispatcher runs on. Scoped to this module because the outbox
 * is currently the only scheduled work in the application (CLAUDE.md §6a).
 */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
class NotificationsConfig {}
