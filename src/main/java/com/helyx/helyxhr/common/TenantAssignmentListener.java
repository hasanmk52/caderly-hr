package com.helyx.helyxhr.common;

import com.helyx.helyxhr.tenant.TenantContext;
import jakarta.persistence.PrePersist;

/**
 * Sets tenant_id from {@link TenantContext} on every insert (CLAUDE.md §5 rule 5). Fail-closed: no
 * active tenant means the write is refused, so a row can never be persisted unscoped.
 */
public class TenantAssignmentListener {

  @PrePersist
  void assignTenant(TenantAwareEntity entity) {
    entity.assignTenantId(TenantContext.require());
  }
}
