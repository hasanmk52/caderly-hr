package com.helyx.helyxhr.common;

import jakarta.persistence.Column;
import jakarta.persistence.MappedSuperclass;
import java.util.UUID;
import org.hibernate.annotations.TenantId;
import org.jspecify.annotations.Nullable;

/**
 * Base for every tenant-scoped entity (CLAUDE.md §5 rule 1). Extending this is ALL an entity needs
 * to be tenant-safe: {@code @TenantId} is Hibernate 7 discriminator multi-tenancy — Hibernate arms
 * the restriction itself (on every query it generates, including {@code find(id)}) and assigns the
 * column on insert, both driven by {@code tenant.TenantIdentifierResolver} — never per entity, and
 * never a hand-written {@code @Filter} or {@code @PrePersist} listener.
 */
@MappedSuperclass
public abstract class TenantAwareEntity extends BaseEntity {

    @TenantId
    @Column(name = "tenant_id", nullable = false, updatable = false)
    private @Nullable UUID tenantId;

    public @Nullable UUID getTenantId() {
        return tenantId;
    }
}
