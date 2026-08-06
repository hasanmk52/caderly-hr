/**
 * System-scoped notification infrastructure.
 *
 * <p>The {@code .system} suffix is load-bearing, not decorative: {@code ArchitectureTest} exempts
 * {@code ..system..} from the "every entity extends TenantAwareEntity" rule by package pattern, so
 * placing an entity here is an explicit statement that it is cross-tenant infrastructure rather
 * than tenant data (ADR 0005 decision B). Do not put tenant-scoped entities in this package.
 */
@NullMarked
package com.helyx.helyxhr.notifications.system;

import org.jspecify.annotations.NullMarked;
