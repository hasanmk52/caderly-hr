package com.helyx.helyxhr.tenant;

import java.util.Optional;

/**
 * Cross-module read access to tenants. This is the project's general rule for all modules: don't
 * reach into another module's internals, go through its facade
 */
public interface TenantFacade {

    Optional<TenantSummary> bySlug(String slug);
}
