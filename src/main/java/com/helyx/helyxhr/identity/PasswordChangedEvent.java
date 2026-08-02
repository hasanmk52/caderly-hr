package com.helyx.helyxhr.identity;

import java.util.UUID;

/**
 * Published after a user's password changes, so their existing sessions can be revoked (PRD
 * §19.1).
 *
 * <p>An event rather than a direct call because {@code identity} must not depend on {@code
 * security} — that direction is already taken and would cycle. It also keeps the invariant off
 * the controller: revocation happens because the password changed, not because someone remembered
 * to ask for it.
 */
public record PasswordChangedEvent(UUID userId) {}
