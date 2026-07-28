package com.helyx.helyxhr.tenant;

import java.util.Optional;

/**
 * Cross-module read access to tenants (CLAUDE.md §4: no reaching into another module's entities).
 */
public interface TenantFacade {

  Optional<TenantSummary> bySlug(String slug);
}
