package com.caderly.caderlyhr.web;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.authentication.logout.LogoutHandler;
import org.springframework.stereotype.Component;

/**
 * Closes the audit trail when an impersonated Super Admin session ends via the ordinary tenant
 * {@code POST /logout} instead of the dedicated "End impersonation" banner link (PRD FR-1.8).
 *
 * <p>Without this, {@link ImpersonationController#end} is the <em>only</em> way a support
 * session's closing {@code DELETE} {@code audit_entry} row gets written — but the impersonated
 * session still renders the ordinary tenant {@code layout.html}, whose topbar carries the normal
 * {@code POST /logout} link. An operator who uses that link instead of "End impersonation" would
 * leave the audit trail with a {@code CREATE} row and no matching {@code DELETE}: the session
 * really ended, but nothing records when.
 *
 * <p>Registered via {@code HttpSecurity.logout(logout -> logout.addLogoutHandler(...))} on the
 * tenant chain only (see {@code security.SecurityConfig}) — Spring Security runs added logout
 * handlers before the built-in {@code SecurityContextLogoutHandler} that invalidates the session,
 * so the session attribute this checks is still readable when {@link #logout} runs. An ordinary
 * tenant session simply has no {@link ImpersonationSession} attribute, so this is a no-op for every
 * normal logout — it only ever fires for a session {@link ImpersonationController#redeem} started.
 *
 * <p>Delegates the actual row-writing to {@link ImpersonationSessionAuditor}, the same class {@code
 * ImpersonationController#end} uses, so the two ways a support session can end cannot produce
 * differently-shaped closing rows.
 *
 * <p><strong>Known, deliberately out-of-scope gap:</strong> a session that ends by the 24-hour
 * absolute cap ({@code security.AbsoluteSessionTimeoutFilter}) or the container's idle timeout,
 * with no further request from the browser at all, still writes no closing row — neither this
 * handler nor {@code ImpersonationController#end} runs without a request. Covering that would need
 * an {@code HttpSessionListener} reacting to session destruction, a separate and larger piece of
 * infrastructure than this — left as a documented, deliberate gap.
 */
@Component
class ImpersonationLogoutHandler implements LogoutHandler {

    private final ImpersonationSessionAuditor auditor;

    ImpersonationLogoutHandler(ImpersonationSessionAuditor auditor) {
        this.auditor = auditor;
    }

    @Override
    public void logout(HttpServletRequest request, HttpServletResponse response, Authentication authentication) {
        ImpersonationSession impersonated = ImpersonationSession.of(request);
        if (impersonated != null) {
            auditor.recordEnd(RequestTenant.of(request), impersonated);
        }
    }
}
