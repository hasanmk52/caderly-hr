package com.caderly.caderlyhr.tenant;

import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * Read-only view of a tenant, safe to hand across modules (entities never leave this package).
 * {@code logoVersion} is non-null exactly when an uploaded logo exists; templates use it both as
 * the "has a logo" flag and as the cache-busting value on {@code /tenant-logo}.
 */
public record TenantSummary(UUID id, String slug, String name, boolean suspended, @Nullable String logoVersion) {

    static TenantSummary of(Tenant tenant) {
        // getId() is non-null for any persisted tenant; lookups only ever see persisted rows.
        return new TenantSummary(
                java.util.Objects.requireNonNull(tenant.getId()),
                tenant.getSlug(),
                tenant.getName(),
                tenant.isSuspended(),
                tenant.getLogoVersion());
    }
}
