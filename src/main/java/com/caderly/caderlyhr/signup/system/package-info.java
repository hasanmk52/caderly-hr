/**
 * System-scoped sign-up storage.
 *
 * <p>The {@code .system} suffix is load-bearing: {@code ArchitectureTest} exempts {@code
 * ..system..} from the "every entity extends TenantAwareEntity" rule, because a pending sign-up
 * exists before its tenant does (ADR 0005 decision B, ADR 0022 decision C). Do not put
 * tenant-scoped entities in this package.
 */
@NullMarked
package com.caderly.caderlyhr.signup.system;

import org.jspecify.annotations.NullMarked;
