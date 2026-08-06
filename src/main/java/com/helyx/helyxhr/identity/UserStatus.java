package com.helyx.helyxhr.identity;

/**
 * Lifecycle of an {@link AppUser} (PRD §21).
 *
 * <p>These map onto Spring Security's {@code UserDetails} flags rather than being branched on in
 * application code, so {@code DaoAuthenticationProvider}'s built-in pre-authentication checks do
 * the rejecting (ADR 0006 decision A).
 */
public enum UserStatus {
    /** Invited but has not set a password yet. Cannot log in. */
    INVITED,

    /** Normal, usable account. */
    ACTIVE,

    /** Temporarily locked by repeated login failures (PRD §19.1). Clears when locked_until passes. */
    LOCKED,

    /** Deactivated by an Admin, or offboarded. Cannot log in; not self-clearing. */
    DISABLED
}
