package com.caderly.caderlyhr.identity;

import com.caderly.caderlyhr.common.NotFoundException;
import com.caderly.caderlyhr.common.SecureToken;
import com.caderly.caderlyhr.tenant.TenantContext;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import java.time.Clock;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Mints and redeems the short-lived tickets that carry a Super Admin from the console onto a
 * tenant's subdomain as one of its Admins (PRD FR-1.8).
 *
 * <p>Why a ticket at all, rather than the console simply writing a tenant session: the two realms
 * are on different hosts (the console on the bare base domain, the tenant on its subdomain), and
 * keeping them on different hosts is what stops one realm's session cookie from being usable in
 * the other ({@code superadmin.SuperAdminSecurityConfig}). A one-use, one-minute
 * bearer in the URL is the narrowest thing that can cross that gap.
 *
 * <p>Tickets live in an in-JVM Caffeine cache, mirroring {@code security.RateLimitFilter}'s
 * buckets and the in-JVM session store — all three become shared state at the same moment, when
 * the deployment goes multi-instance. A ticket that does not survive a restart is the correct
 * failure mode for a value with a sixty-second life: the operator clicks the link again.
 *
 * <p>The cache's own {@code expireAfterWrite} is a memory bound, not the expiry check. Eviction
 * runs on Caffeine's own ticker, which no test can move; {@link ImpersonationTicket#expiresAt} is
 * compared against the injected {@link Clock}, which is what makes the lifetime testable at its
 * real duration instead of by sleeping.
 */
@Service
public class ImpersonationService {

    private static final Logger log = LoggerFactory.getLogger(ImpersonationService.class);

    /**
     * Long enough for a redirect and a page load, short enough that a ticket captured from a
     * proxy log or browser history is already dead by the time anyone reads it.
     */
    private static final Duration TICKET_LIFETIME = Duration.ofSeconds(60);

    private final Cache<String, ImpersonationTicket> tickets =
            Caffeine.newBuilder().maximumSize(1_000).expireAfterWrite(TICKET_LIFETIME).build();

    private final AppUserRepository users;
    private final Clock clock;

    ImpersonationService(AppUserRepository users, Clock clock) {
        this.users = users;
        this.clock = clock;
    }

    /**
     * Issues a ticket for {@code targetUserId} in {@code tenantId}, returning the raw token to put
     * in the redirect URL.
     *
     * <p>{@link SecureToken#generate()} is reused purely for its 32 bytes of {@code SecureRandom}
     * (PRD §19.1) — the token is <em>not</em> hashed the way an invite or reset token is, and that
     * is not an oversight. Hashing exists so a database leak yields nothing redeemable; this value
     * never reaches a database. It lives in process memory for at most a minute, where a hash
     * would protect against nothing and only cost a lookup.
     */
    public String mint(UUID superAdminId, String superAdminEmail, UUID tenantId, UUID targetUserId) {
        String token = SecureToken.generate();
        tickets.put(
                token,
                new ImpersonationTicket(
                        superAdminId,
                        superAdminEmail,
                        tenantId,
                        targetUserId,
                        clock.instant().plus(TICKET_LIFETIME)));
        // The token itself is never logged (CLAUDE.md §6 A09) — only who asked for what.
        log.info(
                "Super Admin {} minted an impersonation ticket for user {} in tenant {}",
                superAdminId,
                targetUserId,
                tenantId);
        return token;
    }

    /**
     * Spends {@code token}, returning its ticket only if it was unspent and unexpired.
     *
     * <p>Single-use is enforced by <em>removing</em> the entry rather than reading it, so the
     * second attempt finds nothing whatever the clock says — including two requests racing on the
     * same token, where the map's atomic removal decides the winner. Expiry is then checked on the
     * already-removed value, which is why an expired ticket is also a consumed one.
     */
    public Optional<ImpersonationTicket> redeem(String token) {
        ImpersonationTicket ticket = tickets.asMap().remove(token);
        if (ticket == null) {
            return Optional.empty();
        }
        if (!ticket.expiresAt().isAfter(clock.instant())) {
            log.warn("Impersonation ticket for tenant {} was presented after it expired", ticket.tenantId());
            return Optional.empty();
        }
        return Optional.of(ticket);
    }

    /**
     * Builds the session principal for an impersonated Admin.
     *
     * <p><strong>Must be called with {@code TenantContext} already set to the ticket's tenant.</strong>
     * There is no tenant argument and no {@code runAsSystem} here on purpose: the lookup is a plain
     * {@code findById} on a {@code @TenantId} entity, so it is scoped to whichever tenant resolved
     * for this request (CLAUDE.md §5 rule 4). The caller is {@code web.ImpersonationController},
     * running on the tenant's own subdomain after {@code TenantResolutionFilter}, so the scoping is
     * already correct — and a target id belonging to some other tenant simply is not found, which
     * is a second, structural refusal behind the controller's own explicit tenant check.
     *
     * @throws NotFoundException if no such user exists in the current tenant.
     */
    @Transactional(readOnly = true)
    public ImpersonatedAdminPrincipal buildImpersonatedPrincipal(UUID targetUserId) {
        AppUser user =
                users.findById(targetUserId)
                        .orElseThrow(
                                () ->
                                        new NotFoundException(
                                                "IMPERSONATION_TARGET_NOT_FOUND", "No such user in this tenant"));

        return new ImpersonatedAdminPrincipal(
                user.requireId(),
                user.email(),
                user.roles(),
                AppUserPrincipal.isEnabled(user),
                !user.isLocked(clock.instant()));
    }

    /**
     * The Super Admin console's target picker for {@code POST
     * /superadmin/tenants/{id}/impersonate}: an arbitrary ACTIVE Admin in {@code tenantId}, if one
     * exists.
     *
     * <p>{@code findAll()} rather than a dedicated query — there is no "find admin users"
     * repository method today, and at this scale (a handful of tenants, each with a handful of
     * users) a full scan costs nothing worth indexing for (CLAUDE.md §11's serial-fan-out
     * precedent, mirrored from {@code people.EmployeeTerminationJob}). If a tenant has more than
     * one ACTIVE Admin, this arbitrarily picks the first one {@code findAll()} returns — an
     * accepted MVP simplification; a full admin-picker UI is out of scope (YAGNI).
     *
     * <p>Sets {@code TenantContext} itself rather than requiring the caller to: {@code app_user} is
     * RLS-protected per tenant, so — exactly as {@link #buildImpersonatedPrincipal} notes for its
     * own {@code findById} — this read needs a real, resolved tenant context, not {@code
     * TenantContext.runAsSystem}'s bypass (ADR 0003).
     */
    public Optional<AdminAccount> findAnyAdmin(UUID tenantId) {
        TenantContext.set(tenantId);
        try {
            return users.findAll().stream()
                    .filter(u -> u.roles().contains(Role.ADMIN) && u.status() == UserStatus.ACTIVE)
                    .findFirst()
                    .map(u -> new AdminAccount(u.requireId(), u.email()));
        } finally {
            TenantContext.clear();
        }
    }

    /** An ACTIVE Admin found by {@link #findAnyAdmin}, ready to be impersonated. */
    public record AdminAccount(UUID userId, String email) {}
}
