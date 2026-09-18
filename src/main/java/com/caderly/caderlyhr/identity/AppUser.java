package com.caderly.caderlyhr.identity;

import com.caderly.caderlyhr.audit.EntityAuditListener;
import com.caderly.caderlyhr.common.TenantAwareEntity;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;

/**
 * A tenant's login identity (PRD §21). Tenant scoping is entirely inherited: extending {@link
 * TenantAwareEntity} is all it takes for Hibernate's {@code @TenantId} restriction and the Postgres
 * RLS policy to apply (ADR 0004). Nothing in this class mentions tenant_id.
 *
 * <p>No public setters (CLAUDE.md §10): every mutation is a named transition so that call sites
 * read as intent and the invariants below stay in one place.
 */
@Entity
@EntityListeners(EntityAuditListener.class)
@Table(name = "app_user")
public class AppUser extends TenantAwareEntity {

    @Column(name = "email", nullable = false)
    private String email;

    @Column(name = "password_hash")
    private @Nullable String passwordHash;

    // Mapped but unread: the columns exist so Phase 1.5 (PRD FR-1.5) is an additive change rather
    // than a migration. No accessors until something needs them.
    @Column(name = "mfa_secret")
    private @Nullable String mfaSecret;

    @Column(name = "mfa_enabled", nullable = false)
    private boolean mfaEnabled;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private UserStatus status;

    @Column(name = "last_login_at")
    private @Nullable Instant lastLoginAt;

    // failed_login_count / failed_login_window_start columns still exist in the DB (ADR 0017
    // left them in place rather than risk a destructive drop-column migration for this phase)
    // but are no longer read or written anywhere: the (email + IP) lockout re-key computes the
    // failure count from audit.LoginAuditRepository instead (identity.LoginAttemptService),
    // superseding ADR 0006 decision B's per-user counter. Nothing in this class maps them.

    @Column(name = "locked_until")
    private @Nullable Instant lockedUntil;

    @Column(name = "invite_token_hash")
    private @Nullable String inviteTokenHash;

    @Column(name = "invite_expires_at")
    private @Nullable Instant inviteExpiresAt;

    /**
     * The per-user iCal feed token (PRD FR-6.5, sub-phase 1.8), stored raw rather than hashed —
     * see ADR 0014. {@code null} until the user's first visit to Settings -> Calendar integration.
     */
    @Column(name = "ical_token")
    private @Nullable String icalToken;

    // EAGER is deliberate: roles are needed on every authentication and there are at most three
    // per user, so the alternative is an N+1 or an @EntityGraph on every lookup for no gain.
    @OneToMany(
            mappedBy = "user",
            cascade = CascadeType.ALL,
            orphanRemoval = true,
            fetch = FetchType.EAGER)
    private Set<UserRole> roles = new LinkedHashSet<>();

    protected AppUser() {}

    private AppUser(String email, UserStatus status) {
        this.email = email;
        this.status = status;
    }

    /**
     * Creates an un-activated user holding an invite token. The raw token is never stored — the
     * caller hashes it and keeps the plaintext only long enough to put it in the email (CLAUDE.md
     * §6 A02).
     */
    public static AppUser invited(String email, String inviteTokenHash, Instant inviteExpiresAt) {
        AppUser user = new AppUser(email, UserStatus.INVITED);
        user.inviteTokenHash = inviteTokenHash;
        user.inviteExpiresAt = inviteExpiresAt;
        return user;
    }

    /** Creates an already-usable user. Used by the dev bootstrap and by tests. */
    public static AppUser active(String email, String passwordHash) {
        AppUser user = new AppUser(email, UserStatus.ACTIVE);
        user.passwordHash = passwordHash;
        return user;
    }

    /**
     * Redeems the invite: sets the password and activates. Clearing the hash is what makes the
     * invite single-use (PRD §19.1) — a replayed link finds no token to match.
     */
    public void acceptInvite(String passwordHash) {
        this.passwordHash = passwordHash;
        this.status = UserStatus.ACTIVE;
        this.inviteTokenHash = null;
        this.inviteExpiresAt = null;
        unlock();
    }

    /** Whether the invite token is still redeemable at {@code now}. */
    public boolean hasValidInvite(Instant now) {
        return inviteTokenHash != null && inviteExpiresAt != null && now.isBefore(inviteExpiresAt);
    }

    /**
     * A successful reset proves mailbox control, so it also lifts any lock the failed attempts
     * that prompted the reset had put in place.
     */
    public void changePassword(String passwordHash) {
        this.passwordHash = passwordHash;
        unlock();
    }

    /**
     * Also clears a LOCKED status. A lapsed lock lets authentication through on the timestamp
     * alone, so without this the status column would stay LOCKED forever on an account that is
     * demonstrably usable.
     */
    public void recordSuccessfulLogin(Instant now) {
        this.lastLoginAt = now;
        unlock();
    }

    /**
     * Locks the account until {@code until}. The trigger decision (how many failures, in what
     * window, from which IP) lives in {@code identity.LoginAttemptService} — it queries {@code
     * audit.LoginAuditRepository} for the (email + IP) count (ADR 0017) and calls this once that
     * count crosses the threshold. The entity's only job is to hold the resulting lock state.
     */
    public void lock(Instant until) {
        this.lockedUntil = until;
        this.status = UserStatus.LOCKED;
    }

    /**
     * Whether the account is locked at {@code now}. Driven by the timestamp rather than the status
     * flag so a lapsed lock needs no scheduled job to clear it.
     */
    public boolean isLocked(Instant now) {
        return lockedUntil != null && now.isBefore(lockedUntil);
    }

    /**
     * Clears a lock. Called on the next successful authentication, not by a scheduled job — {@link
     * #isLocked} already lets a lapsed lock through on the timestamp alone, but {@code status}
     * needs an explicit clear or it would read LOCKED forever on an account that is demonstrably
     * usable again.
     */
    public void unlock() {
        this.lockedUntil = null;
        if (this.status == UserStatus.LOCKED) {
            this.status = UserStatus.ACTIVE;
        }
    }

    public void disable() {
        this.status = UserStatus.DISABLED;
    }

    /**
     * Sets (or rotates) the iCal feed token. A plain overwrite is what makes rotation instant:
     * whoever held the previous URL loses access on the next request (CalendarTokenService).
     */
    public void issueIcalToken(String rawToken) {
        this.icalToken = rawToken;
    }

    public void grant(Role role) {
        if (roles.stream().noneMatch(assignment -> assignment.role() == role)) {
            roles.add(new UserRole(this, role));
        }
    }

    /**
     * Removes the role if held; a no-op otherwise. {@code orphanRemoval = true} on {@link #roles}
     * means removing from the set here deletes the underlying {@link UserRole} row on flush — no
     * separate repository call needed.
     */
    public void revoke(Role role) {
        roles.removeIf(assignment -> assignment.role() == role);
    }

    /** The roles held by this user, as an unmodifiable snapshot. */
    public Set<Role> roles() {
        return roles.stream().map(UserRole::role).collect(Collectors.toUnmodifiableSet());
    }

    public String email() {
        return email;
    }

    public @Nullable String passwordHash() {
        return passwordHash;
    }

    public UserStatus status() {
        return status;
    }

    public @Nullable Instant lastLoginAt() {
        return lastLoginAt;
    }

    public @Nullable Instant lockedUntil() {
        return lockedUntil;
    }

    public @Nullable String inviteTokenHash() {
        return inviteTokenHash;
    }

    public @Nullable String icalToken() {
        return icalToken;
    }
}
