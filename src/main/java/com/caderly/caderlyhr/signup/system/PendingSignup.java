package com.caderly.caderlyhr.signup.system;

import com.caderly.caderlyhr.common.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * A sign-up waiting for its email to be confirmed (ADR 0022 decision C). Holds its workspace
 * address until {@link #expiresAt}. Extends {@link BaseEntity}, not {@code TenantAwareEntity}: no
 * tenant exists yet — see the migration header.
 */
@Entity
@Table(name = "pending_signup")
public class PendingSignup extends BaseEntity {

    @Column(name = "slug", nullable = false, length = 50)
    private String slug;

    @Column(name = "company_name", nullable = false, length = 200)
    private String companyName;

    @Column(name = "timezone", nullable = false, length = 50)
    private String timezone;

    @Column(name = "weekend_days", nullable = false)
    private int weekendDays;

    @Column(name = "admin_name", nullable = false, length = 200)
    private String adminName;

    @Column(name = "admin_email", nullable = false)
    private String adminEmail;

    /** SHA-256 of the raw token; the raw token is never stored (CLAUDE.md §6 A02). */
    @Column(name = "token_hash", nullable = false, length = 64)
    private String tokenHash;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    protected PendingSignup() {
        // JPA only
        this.slug = "";
        this.companyName = "";
        this.timezone = "UTC";
        this.adminName = "";
        this.adminEmail = "";
        this.tokenHash = "";
        this.expiresAt = Instant.EPOCH;
    }

    public PendingSignup(
            String slug,
            String companyName,
            String timezone,
            int weekendDays,
            String adminName,
            String adminEmail,
            String tokenHash,
            Instant expiresAt) {
        this.slug = slug;
        this.companyName = companyName;
        this.timezone = timezone;
        this.weekendDays = weekendDays;
        this.adminName = adminName;
        this.adminEmail = adminEmail;
        this.tokenHash = tokenHash;
        this.expiresAt = expiresAt;
    }

    public boolean isExpired(Instant now) {
        return !now.isBefore(expiresAt);
    }

    public String slug() {
        return slug;
    }

    public String companyName() {
        return companyName;
    }

    public String timezone() {
        return timezone;
    }

    public int weekendDays() {
        return weekendDays;
    }

    public String adminName() {
        return adminName;
    }

    public String adminEmail() {
        return adminEmail;
    }

    public String tokenHash() {
        return tokenHash;
    }

    public Instant expiresAt() {
        return expiresAt;
    }
}
