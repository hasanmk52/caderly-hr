package com.caderly.caderlyhr.web;

import com.caderly.caderlyhr.common.NotFoundException;
import com.caderly.caderlyhr.identity.ImpersonatedAdminPrincipal;
import com.caderly.caderlyhr.identity.ImpersonationService;
import com.caderly.caderlyhr.identity.ImpersonationTicket;
import com.caderly.caderlyhr.security.SecurityPaths;
import com.caderly.caderlyhr.tenant.TenantSummary;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.session.SessionRegistry;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * The two ends of a Super Admin support session (PRD FR-1.8): redeeming a ticket minted in the
 * operator console, and giving the operator a way out of the session they started.
 *
 * <p><strong>Both endpoints live in the tenant-facing chain, on the tenant's own subdomain.</strong>
 * That is the entire design. The Super Admin realm keeps its {@code SecurityContext} under its own
 * session key precisely so an operator session can never satisfy a tenant page (sub-phase 1.13's
 * fix, {@code superadmin.SuperAdminSecurityConfig}); impersonation is the one sanctioned way across
 * that line, and it crosses it by <em>authenticating as the Admin</em> in the tenant realm rather
 * than by teaching the tenant realm to accept operator principals. A reviewer should be able to
 * confirm that nothing here widens what a Super Admin session alone can reach.
 *
 * <p>See {@code SecurityPaths#IMPERSONATE_PATH} for why the redeem URL is not spelled
 * {@code /superadmin-impersonate}.
 */
@Controller
class ImpersonationController {

    private static final Logger log = LoggerFactory.getLogger(ImpersonationController.class);

    /** One destination for every rejection, so a probe learns nothing about which check failed. */
    private static final String FAILURE_REDIRECT =
            "redirect:" + SecurityPaths.LOGIN_PATH + "?impersonationFailed";

    private final ImpersonationService impersonation;
    private final ImpersonationSessionAuditor auditor;
    private final SecurityContextRepository securityContextRepository;
    private final SessionRegistry sessionRegistry;

    ImpersonationController(
            ImpersonationService impersonation,
            ImpersonationSessionAuditor auditor,
            SecurityContextRepository securityContextRepository,
            SessionRegistry sessionRegistry) {
        this.impersonation = impersonation;
        this.auditor = auditor;
        this.securityContextRepository = securityContextRepository;
        this.sessionRegistry = sessionRegistry;
    }

    /**
     * Spends a ticket and leaves behind a session authenticated as the target Admin.
     *
     * <p>{@code permitAll()} because the browser arriving here has no tenant session yet — the
     * ticket is the credential, and {@code ImpersonationService} has already made it single-use and
     * one minute old at most. Everything below fails closed to one redirect.
     */
    @GetMapping(SecurityPaths.IMPERSONATE_PATH)
    @PreAuthorize("permitAll()")
    String redeem(
            @RequestParam(name = "token", required = false) @Nullable String token,
            HttpServletRequest request,
            HttpServletResponse response) {

        // Optional rather than required so that a bare GET with no token lands on the same
        // redirect as every other rejection. A required parameter would answer a 400 instead,
        // which is the one response that distinguishes "this endpoint exists" from "it does not".
        if (token == null) {
            return FAILURE_REDIRECT;
        }

        Optional<ImpersonationTicket> redeemed = impersonation.redeem(token);
        if (redeemed.isEmpty()) {
            return FAILURE_REDIRECT;
        }
        ImpersonationTicket ticket = redeemed.get();

        // The ticket names the tenant it was minted for; the subdomain decides which tenant this
        // request is in. They have to be the same tenant, or a ticket for one customer would
        // authenticate its holder on another's subdomain — where @TenantId would then scope every
        // page to *that* tenant's data. Checked here as well as relied upon in the lookup below,
        // because "the user id happens not to exist over there" is luck, not a boundary.
        TenantSummary tenant = RequestTenant.of(request);
        if (!ticket.tenantId().equals(tenant.id())) {
            log.warn(
                    "Rejected an impersonation ticket minted for tenant {} presented on tenant {}",
                    ticket.tenantId(),
                    tenant.id());
            return FAILURE_REDIRECT;
        }

        ImpersonatedAdminPrincipal principal;
        try {
            principal = impersonation.buildImpersonatedPrincipal(ticket.targetUserId());
        } catch (NotFoundException exception) {
            // The account was removed between minting and redeeming — a sixty-second window, but
            // this is the one place a missing target must not become a 404 page that says so.
            log.warn("Impersonation target {} no longer exists in tenant {}", ticket.targetUserId(), tenant.id());
            return FAILURE_REDIRECT;
        }

        // Audit before the session exists, not after. The invariant worth holding is "an
        // impersonated session implies a start row": a failed insert must abort the redemption
        // rather than leave an authenticated operator behind with nothing in the trail. Writing an
        // extra row for a redemption that then fails is the harmless direction of that trade.
        // (The end() path below already orders itself this way for the same reason.)
        String correlationId = UUID.randomUUID().toString();
        auditor.recordStart(tenant, ticket.superAdminId(), ticket.superAdminEmail(), principal.getUsername(), correlationId);

        // Session fixation, the same defence formLogin applies with sessionFixation().newSession():
        // this endpoint is reachable without authentication, so a session id planted beforehand
        // must not survive into the authenticated session.
        HttpSession previous = request.getSession(false);
        if (previous != null) {
            previous.invalidate();
        }

        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(
                UsernamePasswordAuthenticationToken.authenticated(
                        principal, null, principal.getAuthorities()));
        SecurityContextHolder.setContext(context);
        // Saved through the tenant chain's own repository bean (security.SecurityConfig), which is
        // what the SecurityContextHolderFilter on the very next request loads from. Constructing a
        // repository here instead would work only for as long as the two constructions agreed.
        securityContextRepository.saveContext(context, request, response);

        HttpSession session = request.getSession(true);
        // A normal form login registers its new session through the chain's
        // SessionAuthenticationStrategy; saving the context directly skips that machinery entirely,
        // so the registration has to happen here. Without it the session is invisible to
        // security.SessionRevoker, and a password change or termination on the very account being
        // impersonated would leave this session alive (CLAUDE.md §6 A07). ImpersonatedAdminPrincipal
        // extends AppUserPrincipal, so the revoker's existing scan matches it with no change there.
        sessionRegistry.registerNewSession(session.getId(), principal);

        new ImpersonationSession(
                        ticket.superAdminId(), ticket.superAdminEmail(), principal.getUsername(), correlationId)
                .storeIn(session);

        log.info(
                "Super Admin {} started an impersonation session as {} in tenant {}",
                ticket.superAdminId(),
                ticket.targetUserId(),
                tenant.id());

        return "redirect:/";
    }

    /**
     * Ends a support session: writes the closing audit row, then drops the session entirely.
     *
     * <p>{@code isAuthenticated()} rather than {@code permitAll()} — ending a session is only
     * meaningful from inside one, and an unauthenticated caller has nothing to end. An ordinary
     * tenant user reaching this (they have no banner to click, but the URL is guessable) has no
     * impersonation attribute in their session, so it is a no-op redirect rather than a logout.
     */
    @PostMapping(SecurityPaths.END_IMPERSONATION_PATH)
    @PreAuthorize("isAuthenticated()")
    String end(HttpServletRequest request) {
        ImpersonationSession impersonated = ImpersonationSession.of(request);
        if (impersonated == null) {
            return "redirect:/";
        }

        // Written before the session goes away: an invalidate that happened without its closing
        // row would leave a session that looks, in the audit trail, like it never ended.
        auditor.recordEnd(RequestTenant.of(request), impersonated);

        HttpSession session = request.getSession(false);
        if (session != null) {
            session.invalidate();
        }
        SecurityContextHolder.clearContext();
        log.info("Super Admin {} ended an impersonation session", impersonated.superAdminId());

        return "redirect:" + SecurityPaths.LOGIN_PATH + "?impersonationEnded";
    }
}
