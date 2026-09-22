package com.caderly.caderlyhr.identity;

import com.caderly.caderlyhr.common.AuditActor;
import java.util.Set;
import java.util.UUID;

/**
 * The principal of a Super Admin support session, acting as one tenant Admin (PRD FR-1.8).
 *
 * <p><strong>A subclass of {@link AppUserPrincipal}, not a wrapper around one.</strong> More than
 * two dozen controller parameters declare {@code @AuthenticationPrincipal AppUserPrincipal}, and Spring's
 * {@code AuthenticationPrincipalArgumentResolver} passes {@code null} for a principal that is not
 * assignable to the declared parameter type. A decorator implementing {@code UserDetails} and
 * delegating every call would satisfy {@code @PreAuthorize} perfectly and still hand {@code null}
 * to {@code ProfileController}, {@code LeaveRequestController}, {@code HomeController} and the
 * rest — an impersonated session that authenticates and then fails on almost every page it can
 * reach. Extending is what makes "behaves exactly like the real Admin" true by construction.
 *
 * <p>Exactly one behaviour differs, and it is the point of the class: {@link #roleNames()} — read
 * only by {@code audit.EntityAuditListener} to fill {@code audit_entry.actor_role} — reports
 * {@link AuditActor#IMPERSONATION_ROLE} instead of the Admin's real roles, so every write made
 * during the session is visibly a support write. {@link AppUserPrincipal#getAuthorities()} is untouched and
 * still carries the real {@code ROLE_ADMIN}, because authorization must behave identically to a
 * normal login; and {@link AuditActor#actorId()} still returns the Admin's own user id, because
 * the write genuinely was made against that account.
 *
 * <p>The password hash is never copied in: nothing re-authenticates this principal, so there is no
 * reason for the hash to sit in an HTTP session (CLAUDE.md §6 A02).
 */
public final class ImpersonatedAdminPrincipal extends AppUserPrincipal {

    ImpersonatedAdminPrincipal(
            UUID userId, String email, Set<Role> roles, boolean enabled, boolean accountNonLocked) {
        super(userId, email, "", roles, enabled, accountNonLocked);
    }

    @Override
    public Set<String> roleNames() {
        return Set.of(AuditActor.IMPERSONATION_ROLE);
    }

    @Override
    public String toString() {
        return "ImpersonatedAdminPrincipal[userId=" + userId() + ", email=" + getUsername() + "]";
    }
}
