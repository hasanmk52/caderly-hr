package com.caderly.caderlyhr.superadmin;

import com.caderly.caderlyhr.common.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import org.jspecify.annotations.Nullable;

/**
 * A cross-tenant operator account for the Super Admin console (PRD FR-1.8, Phase 1.13).
 *
 * <p>Extends {@link BaseEntity}, <strong>not</strong> {@code TenantAwareEntity} — a Super Admin
 * belongs to no tenant by definition, mirroring {@code tenant.Tenant} itself (ArchitectureTest
 * already exempts {@code com.caderly.caderlyhr.superadmin..} from the "every entity extends
 * TenantAwareEntity" rule). No {@code @EntityListeners(EntityAuditListener.class)}: that
 * listener's callback methods only accept {@code TenantAwareEntity}, so it cannot apply here —
 * and with at most a handful of rows, created once by {@link SuperAdminBootstrap}, there is no
 * lifecycle worth entity-auditing.
 */
@Entity
@Table(name = "super_admin")
public class SuperAdmin extends BaseEntity {

    @Column(name = "email", nullable = false, unique = true)
    private String email;

    @Column(name = "password_hash", nullable = false)
    private String passwordHash;

    // Mapped but unread: MFA for the Super Admin realm is deferred per the design plan, mirroring
    // identity.AppUser#mfaSecret's exact "the column exists so enabling it later is additive"
    // reasoning. No accessor until something needs one.
    @Column(name = "mfa_secret")
    private @Nullable String mfaSecret;

    protected SuperAdmin() {}

    public SuperAdmin(String email, String passwordHash) {
        this.email = email;
        this.passwordHash = passwordHash;
    }

    public String email() {
        return email;
    }

    public String passwordHash() {
        return passwordHash;
    }
}
