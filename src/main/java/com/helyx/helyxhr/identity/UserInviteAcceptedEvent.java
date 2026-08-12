package com.helyx.helyxhr.identity;

import java.util.UUID;

/**
 * Published after an invited user sets their password and activates (PRD §14.2). An event rather
 * than a direct call for the same reason as {@link PasswordChangedEvent}: {@code identity} must
 * not depend on {@code people} (that dependency already runs the other way — {@code
 * people.Employee} references an {@code AppUser} by id), and a cycle would result.
 */
public record UserInviteAcceptedEvent(UUID userId) {}
