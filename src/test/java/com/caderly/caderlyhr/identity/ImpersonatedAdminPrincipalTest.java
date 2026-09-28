package com.caderly.caderlyhr.identity;

import static org.assertj.core.api.Assertions.assertThat;

import com.caderly.caderlyhr.common.AuditActor;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.GrantedAuthority;

/**
 * The one deliberate divergence between an impersonated principal and a real one: {@code
 * roleNames()} (what the audit trail reads) says {@code SUPERADMIN_IMPERSONATING}, while {@code
 * getAuthorities()} (what {@code @PreAuthorize} reads) stays the Admin's real roles. Both halves
 * matter — swapping them would either hide the impersonation from the audit log or make every
 * tenant page 403 during a support session.
 */
class ImpersonatedAdminPrincipalTest {

    private static final UUID TARGET_USER_ID = UUID.randomUUID();
    private static final String TARGET_EMAIL = "admin@acme.test";

    @Test
    void roleNames_reportTheImpersonationMarkerInsteadOfTheRealRoles() {
        assertThat(principal().roleNames()).containsExactly(AuditActor.IMPERSONATION_ROLE);
    }

    @Test
    void getAuthorities_stayTheRealRolesSoAuthorizationBehavesLikeANormalLogin() {
        assertThat(principal().getAuthorities())
                .extracting(GrantedAuthority::getAuthority)
                .containsExactlyInAnyOrder("ROLE_ADMIN", "ROLE_EMPLOYEE");
    }

    @Test
    void identityFields_areTheImpersonatedAdminsOwn() {
        ImpersonatedAdminPrincipal principal = principal();

        assertThat(principal.getUsername()).isEqualTo(TARGET_EMAIL);
        assertThat(principal.userId()).isEqualTo(TARGET_USER_ID);
        // audit_entry.actor_user_id is this, never the Super Admin's id: the write really was
        // made against this Admin's account, and actor_role is what records who was driving.
        assertThat(principal.actorId()).isEqualTo(TARGET_USER_ID);
    }

    @Test
    void getPassword_isNeverTheStoredHash() {
        // Nothing authenticates this principal against a password, so the hash has no reason to
        // be copied into the session (CLAUDE.md §6 A02).
        assertThat(principal().getPassword()).isEmpty();
    }

    @Test
    void isAnAppUserPrincipal_soControllersResolveItAsTheAuthenticationPrincipal() {
        // 27 controller methods declare @AuthenticationPrincipal AppUserPrincipal. Spring's
        // argument resolver passes null for a principal that is not assignable to the declared
        // type, so a principal that merely *wrapped* an AppUserPrincipal would make every one of
        // those pages fail during an impersonated session. See ImpersonatedAdminPrincipal's javadoc.
        assertThat(principal()).isInstanceOf(AppUserPrincipal.class);
    }

    @Test
    void accountFlags_arePassedThroughFromTheLoadedUser() {
        ImpersonatedAdminPrincipal locked =
                new ImpersonatedAdminPrincipal(TARGET_USER_ID, TARGET_EMAIL, Set.of(Role.ADMIN), false, false);

        assertThat(locked.isEnabled()).isFalse();
        assertThat(locked.isAccountNonLocked()).isFalse();
        assertThat(locked.isAccountNonExpired()).isTrue();
        assertThat(locked.isCredentialsNonExpired()).isTrue();
    }

    private static ImpersonatedAdminPrincipal principal() {
        return new ImpersonatedAdminPrincipal(
                TARGET_USER_ID, TARGET_EMAIL, Set.of(Role.ADMIN, Role.EMPLOYEE), true, true);
    }
}
