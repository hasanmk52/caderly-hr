package com.helyx.helyxhr.identity;

import com.helyx.helyxhr.common.TenantAwareEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

/**
 * One role held by one user.
 *
 * <p>Carries a synthetic UUID primary key from {@code BaseEntity} rather than PRD §21's composite
 * {@code (user_id, role)}, so it can extend {@link TenantAwareEntity} like every other
 * tenant-scoped table and inherit {@code @TenantId} + RLS with no entity-specific tenancy code.
 * The unique constraint below carries the semantic key the composite PK expressed. See ADR 0005
 * decision A.
 *
 * <p>Managed through {@link AppUser#grant} / {@link AppUser#revoke}; there is no reason to
 * construct one directly.
 */
@Entity
@Table(
        name = "user_role",
        uniqueConstraints =
                @UniqueConstraint(
                        name = "user_role_tenant_id_user_id_role_key",
                        columnNames = {"tenant_id", "user_id", "role"}))
public class UserRole extends TenantAwareEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private AppUser user;

    @Enumerated(EnumType.STRING)
    @Column(name = "role", nullable = false, length = 20)
    private Role role;

    protected UserRole() {}

    UserRole(AppUser user, Role role) {
        this.user = user;
        this.role = role;
    }

    public AppUser user() {
        return user;
    }

    public Role role() {
        return role;
    }
}
