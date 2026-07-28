package com.helyx.helyxhr.common;

import jakarta.persistence.Column;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.MappedSuperclass;
import java.util.UUID;
import org.hibernate.annotations.Filter;
import org.hibernate.annotations.FilterDef;
import org.hibernate.annotations.ParamDef;
import org.jspecify.annotations.Nullable;

/**
 * Base for every tenant-scoped entity (CLAUDE.md §5 rule 1). Extending this is ALL an entity needs
 * to be tenant-safe: the Hibernate filter, RLS policy (via the migration template), and tenant_id
 * assignment are wired here and in {@link TenantAssignmentListener} — never per entity.
 */
@MappedSuperclass
@FilterDef(
    name = TenantAwareEntity.TENANT_FILTER,
    parameters = @ParamDef(name = TenantAwareEntity.TENANT_FILTER_PARAM, type = UUID.class))
@Filter(name = TenantAwareEntity.TENANT_FILTER, condition = "tenant_id = :tenantId")
@EntityListeners(TenantAssignmentListener.class)
public abstract class TenantAwareEntity extends BaseEntity {

  public static final String TENANT_FILTER = "tenantFilter";
  public static final String TENANT_FILTER_PARAM = "tenantId";

  @Column(name = "tenant_id", nullable = false, updatable = false)
  private @Nullable UUID tenantId;

  public @Nullable UUID getTenantId() {
    return tenantId;
  }

  // Package-private by design: only TenantAssignmentListener assigns tenant_id,
  // always from TenantContext — never caller-supplied (CLAUDE.md §5 rule 5).
  void assignTenantId(UUID tenantId) {
    this.tenantId = tenantId;
  }
}
