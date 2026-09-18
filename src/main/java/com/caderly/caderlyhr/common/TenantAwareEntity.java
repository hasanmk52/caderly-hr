package com.caderly.caderlyhr.common;

import jakarta.persistence.Column;
import jakarta.persistence.MappedSuperclass;
import jakarta.persistence.Transient;
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

    /**
     * The entity's own-field JSON as of its last load or flush — {@code audit.EntityAuditListener}'s
     * "before" state for an update (ADR 0017). Lives here, on the shared base every audited entity
     * already extends, rather than as a per-entity field repeated across all seventeen audited
     * classes, or a bespoke marker interface each would have to implement. {@code transient} (the
     * keyword, not just {@code @Transient}) so the listener's own reflection-based field scan skips
     * it the same way it skips every other non-persistent field.
     */
    @Transient private transient @Nullable String auditSnapshot;

    public @Nullable UUID getTenantId() {
        return tenantId;
    }

    public @Nullable String auditSnapshot() {
        return auditSnapshot;
    }

    public void setAuditSnapshot(@Nullable String auditSnapshot) {
        this.auditSnapshot = auditSnapshot;
    }
}
