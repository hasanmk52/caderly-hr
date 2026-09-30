package com.caderly.caderlyhr.common;

import java.util.Set;
import java.util.UUID;

/**
 * What {@code audit.EntityAuditListener} needs from an authenticated principal in order to attribute a
 * write. Implemented by {@code identity.AppUserPrincipal}.
 *
 * <p>Lives in {@code common} — the one package every module already depends on — specifically so
 * {@code audit} never has to depend on {@code identity} to read it. {@code identity} depends on
 * {@code audit} instead (the (email + IP) lockout re-key needs {@code audit.LoginAuditRepository},
 * ADR 0017), and a dependency the other way would be a package cycle.
 */
public interface AuditActor {

    /**
     * The {@code actor_role} a Super Admin support session records, in place of the impersonated
     * Admin's real roles (PRD FR-1.8).
     *
     * <p>Defined here because both sides of the contract need the same literal and neither package
     * may import the other: {@code identity.ImpersonatedAdminPrincipal} returns it from {@link
     * #roleNames()}, and {@code audit.EntityAuditListener} recognises it when reducing a role set
     * to one column value. It is not a member of {@code identity.Role} — nobody can be granted it;
     * it is a label on the audit trail. 24 characters, against {@code audit_entry.actor_role}'s
     * {@code varchar(30)} (widened by the sub-phase 1.13 migration for exactly this value).
     */
    String IMPERSONATION_ROLE = "SUPERADMIN_IMPERSONATING";

    UUID actorId();

    /** Role names as Spring Security sees them (e.g. {@code "ADMIN"}), never the {@code ROLE_}-prefixed authority string. */
    Set<String> roleNames();
}
