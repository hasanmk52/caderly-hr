package com.caderly.caderlyhr.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.caderly.caderlyhr.common.NotFoundException;
import com.caderly.caderlyhr.support.MutableClock;
import com.caderly.caderlyhr.support.MutableClockConfiguration;
import com.caderly.caderlyhr.tenantisolation.TenantIsolationTestBase;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.security.core.GrantedAuthority;

/**
 * Ticket discipline for the impersonation hand-off (PRD FR-1.8): a ticket is good for one
 * redemption, inside one minute, and the principal it builds is scoped to the tenant whose
 * subdomain redeemed it.
 */
@Import(MutableClockConfiguration.class)
class ImpersonationServiceTest extends TenantIsolationTestBase {

    @Autowired private ImpersonationService impersonation;
    @Autowired private AppUserRepository users;
    @Autowired private MutableClock clock;

    @Test
    void mint_thenRedeem_returnsTheMintedTicket() {
        UUID superAdminId = UUID.randomUUID();
        UUID targetUserId = UUID.randomUUID();

        String token = impersonation.mint(superAdminId, "root@caderly.test", tenantA, targetUserId);

        ImpersonationTicket ticket = impersonation.redeem(token).orElseThrow();
        assertThat(ticket.superAdminId()).isEqualTo(superAdminId);
        assertThat(ticket.superAdminEmail()).isEqualTo("root@caderly.test");
        assertThat(ticket.tenantId()).isEqualTo(tenantA);
        assertThat(ticket.targetUserId()).isEqualTo(targetUserId);
    }

    @Test
    void redeem_aSecondTimeWithTheSameToken_returnsEmpty() {
        // Single-use by construction: redeem removes the entry rather than reading it, so a
        // token captured from a proxy log or browser history is already spent.
        String token = impersonation.mint(UUID.randomUUID(), "root@caderly.test", tenantA, UUID.randomUUID());
        assertThat(impersonation.redeem(token)).isPresent();

        assertThat(impersonation.redeem(token)).isEmpty();
    }

    @Test
    void redeem_afterTheTicketLifetimeHasElapsed_returnsEmpty() {
        String token = impersonation.mint(UUID.randomUUID(), "root@caderly.test", tenantA, UUID.randomUUID());

        clock.advance(Duration.ofSeconds(61));

        assertThat(impersonation.redeem(token)).isEmpty();
    }

    @Test
    void redeem_anUnknownToken_returnsEmpty() {
        assertThat(impersonation.redeem("not-a-token-anyone-minted")).isEmpty();
    }

    @Test
    void redeem_anExpiredToken_stillConsumesIt() {
        // Expiry and single-use are independent: the removal happens first, so a ticket that
        // expired cannot be retried by racing the clock back (or by a second request arriving
        // while the first was still in flight).
        String token = impersonation.mint(UUID.randomUUID(), "root@caderly.test", tenantA, UUID.randomUUID());
        clock.advance(Duration.ofSeconds(61));
        assertThat(impersonation.redeem(token)).isEmpty();

        clock.advance(Duration.ofSeconds(-61));
        assertThat(impersonation.redeem(token)).isEmpty();
    }

    @Test
    void buildImpersonatedPrincipal_carriesTheTargetsIdentityAndRealAuthorities() {
        UUID adminId = seedAdmin(tenantA);

        ImpersonatedAdminPrincipal principal =
                asTenant(tenantA, () -> impersonation.buildImpersonatedPrincipal(adminId));

        assertThat(principal.userId()).isEqualTo(adminId);
        assertThat(principal.getAuthorities())
                .extracting(GrantedAuthority::getAuthority)
                .containsExactly("ROLE_ADMIN");
        assertThat(principal.roleNames()).containsExactly("SUPERADMIN_IMPERSONATING");
    }

    @Test
    void buildImpersonatedPrincipal_forAUserInAnotherTenant_throwsNotFound() {
        // The service takes no tenant argument on purpose: it relies on @TenantId scoping the
        // findById, so a target id from another tenant simply does not exist here (CLAUDE.md §5
        // rule 4). This is the last line of defence behind the controller's own tenant check.
        UUID adminInA = seedAdmin(tenantA);

        assertThatThrownBy(() -> asTenant(tenantB, () -> impersonation.buildImpersonatedPrincipal(adminInA)))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    void mint_issuesADistinctTokenEachTime() {
        String first = impersonation.mint(UUID.randomUUID(), "root@caderly.test", tenantA, UUID.randomUUID());
        String second = impersonation.mint(UUID.randomUUID(), "root@caderly.test", tenantA, UUID.randomUUID());

        assertThat(first).isNotEqualTo(second);
        assertThat(impersonation.redeem(first)).isPresent();
        assertThat(impersonation.redeem(second)).isPresent();
    }

    private UUID seedAdmin(UUID tenantId) {
        return asTenant(
                tenantId,
                () -> {
                    AppUser admin = AppUser.active("admin-" + UUID.randomUUID() + "@example.test", "hash");
                    admin.grant(Role.ADMIN);
                    return users.save(admin).getId();
                });
    }
}
