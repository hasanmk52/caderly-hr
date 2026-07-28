package com.helyx.helyxhr.tenant;

import java.util.UUID;

/** Read-only view of a tenant, safe to hand across modules (entities never leave this package). */
public record TenantSummary(UUID id, String slug, String name, boolean suspended) {

  static TenantSummary of(Tenant tenant) {
    // getId() is non-null for any persisted tenant; lookups only ever see persisted rows.
    return new TenantSummary(
        java.util.Objects.requireNonNull(tenant.getId()),
        tenant.getSlug(),
        tenant.getName(),
        tenant.isSuspended());
  }
}
