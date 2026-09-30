package com.caderly.caderlyhr.identity;

import java.time.Instant;
import java.util.UUID;

/**
 * A one-minute, one-use hand-off from the Super Admin console to a tenant subdomain (PRD FR-1.8).
 *
 * <p>Deliberately not an entity and never persisted: {@link ImpersonationService} holds these in
 * an in-JVM cache keyed by an opaque token. A row in Postgres would outlive the JVM, survive a
 * restart, and need its own cleanup job — for a value whose entire purpose is to be spent within
 * sixty seconds of being issued.
 *
 * <p>{@code superAdminEmail} is carried here rather than looked up at redemption time because the
 * redeeming request runs on a tenant subdomain, where reading {@code super_admin} would mean a
 * second cross-tenant {@code runAsSystem} hop for a string the minting side already had in hand.
 */
public record ImpersonationTicket(
        UUID superAdminId, String superAdminEmail, UUID tenantId, UUID targetUserId, Instant expiresAt) {}
