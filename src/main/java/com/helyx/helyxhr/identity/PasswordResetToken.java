package com.helyx.helyxhr.identity;

import com.helyx.helyxhr.common.TenantAwareEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.Instant;
import org.jspecify.annotations.Nullable;

/**
 * A single-use, time-limited password reset grant (PRD §19.1, FR-1.3).
 *
 * <p>Only the SHA-256 hash of the token is stored; the raw 32 random bytes exist in the reset
 * email and nowhere else, so a database leak yields nothing usable (CLAUDE.md §6 A02).
 *
 * <p>Gains {@code tenant_id} and the {@code BaseEntity} timestamps relative to PRD §21, as
 * required by CLAUDE.md §5 rule 1 (ADR 0006 decision D).
 */
@Entity
@Table(name = "password_reset_token")
public class PasswordResetToken extends TenantAwareEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private AppUser user;

    @Column(name = "token_hash", nullable = false)
    private String tokenHash;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "used_at")
    private @Nullable Instant usedAt;

    protected PasswordResetToken() {}

    public PasswordResetToken(AppUser user, String tokenHash, Instant expiresAt) {
        this.user = user;
        this.tokenHash = tokenHash;
        this.expiresAt = expiresAt;
    }

    /** Redeemable only if never used and not yet expired. */
    public boolean isRedeemable(Instant now) {
        return usedAt == null && now.isBefore(expiresAt);
    }

    /** Stamps the token as spent. Enforces single use — a replayed link finds usedAt non-null. */
    public void markUsed(Instant now) {
        this.usedAt = now;
    }

    public AppUser user() {
        return user;
    }

    public Instant expiresAt() {
        return expiresAt;
    }

    public @Nullable Instant usedAt() {
        return usedAt;
    }
}
