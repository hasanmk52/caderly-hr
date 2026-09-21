package com.caderly.caderlyhr.superadmin;

import com.caderly.caderlyhr.audit.LoginAuditService;
import com.caderly.caderlyhr.audit.system.LoginAudit.FailureReason;
import com.caderly.caderlyhr.common.ClientIpResolver;
import com.caderly.caderlyhr.tenant.TenantContext;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.authentication.AuthenticationFailureHandler;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.security.web.authentication.SavedRequestAwareAuthenticationSuccessHandler;
import org.springframework.security.web.authentication.SimpleUrlAuthenticationFailureHandler;

/**
 * Writes a {@code login_audit} row for every Super Admin login attempt, success or failure
 * (CLAUDE.md §6 A09, PRD §18.1 / FR-11.2), then hands off to the redirect this realm's form login
 * would have performed anyway.
 *
 * <p>Handlers rather than {@code security.LoginAttemptListener}'s global {@code @EventListener},
 * which is the obvious-looking reuse and would break the realm. That listener resolves the account
 * through {@code AppUserRepository} and writes through a tenant-scoped {@code @Transactional}
 * path; on a {@code /superadmin/**} thread {@code TenantResolutionFilter} never ran, so {@code
 * TenantContext} is empty and {@code TenantSessionVariableListener.afterBegin} rejects the
 * transaction outright. That is why {@code SuperAdminSecurityConfig} builds its {@code
 * ProviderManager} with the default null event publisher, and why this realm's auditing hangs off
 * the filter's own handlers instead of off authentication events.
 *
 * <p>The rows it writes carry {@code tenant_id = null} — the shape {@link
 * com.caderly.caderlyhr.audit.system.LoginAudit} was already designed for, since an attempt
 * against an unknown address has no tenant to attribute either.
 */
class SuperAdminLoginAuditHandler
        implements AuthenticationSuccessHandler, AuthenticationFailureHandler {

    private final LoginAuditService loginAudit;
    private final SuperAdminRepository superAdmins;
    private final AuthenticationSuccessHandler onSuccess;
    private final AuthenticationFailureHandler onFailure;

    SuperAdminLoginAuditHandler(
            LoginAuditService loginAudit,
            SuperAdminRepository superAdmins,
            String successUrl,
            String failureUrl) {
        this.loginAudit = loginAudit;
        this.superAdmins = superAdmins;
        // Exactly what defaultSuccessUrl(successUrl, true) and failureUrl(failureUrl) construct,
        // so auditing is the only behavioural change: the saved request is still ignored on the
        // way in, and every failure still lands on one generic destination (CLAUDE.md §6 A07).
        SavedRequestAwareAuthenticationSuccessHandler success =
                new SavedRequestAwareAuthenticationSuccessHandler();
        success.setDefaultTargetUrl(successUrl);
        success.setAlwaysUseDefaultTargetUrl(true);
        this.onSuccess = success;
        this.onFailure = new SimpleUrlAuthenticationFailureHandler(failureUrl);
    }

    @Override
    public void onAuthenticationSuccess(
            HttpServletRequest request, HttpServletResponse response, Authentication authentication)
            throws IOException, ServletException {
        UUID superAdminId =
                authentication.getPrincipal() instanceof SuperAdminPrincipal principal
                        ? principal.superAdminId()
                        : null;
        record(request, authentication.getName(), superAdminId, true, null);
        onSuccess.onAuthenticationSuccess(request, response, authentication);
    }

    /**
     * {@code AuthenticationException} carries no attempted username, so the address comes from the
     * submitted form field — the same {@code usernameParameter} the login filter read.
     *
     * <p>Ambiguous by design, exactly as on the tenant realm: {@code hideUserNotFoundExceptions}
     * collapses "no such operator" and "wrong password" into one {@code BadCredentialsException},
     * and the lookup below tells them apart for the audit row only. The response is one redirect
     * either way, so this never becomes an account-existence oracle.
     */
    @Override
    public void onAuthenticationFailure(
            HttpServletRequest request, HttpServletResponse response, AuthenticationException failure)
            throws IOException, ServletException {
        String email = request.getParameter("email");
        String attempted = email == null ? "" : email;
        TenantContext.runAsSystem(
                "audit super admin login",
                () -> {
                    UUID superAdminId =
                            superAdmins.findByEmail(attempted).map(SuperAdmin::requireId).orElse(null);
                    FailureReason reason =
                            superAdminId == null ? FailureReason.UNKNOWN_EMAIL : FailureReason.BAD_CREDENTIALS;
                    write(request, attempted, superAdminId, false, reason);
                    return null;
                });
        onFailure.onAuthenticationFailure(request, response, failure);
    }

    private void record(
            HttpServletRequest request,
            String email,
            @Nullable UUID superAdminId,
            boolean success,
            @Nullable FailureReason reason) {
        // Global Constraint 1: TenantContext is empty on every thread in this realm, so the
        // audit write needs the system bypass like any other database call reachable from here.
        TenantContext.runAsSystem(
                "audit super admin login",
                () -> {
                    write(request, email, superAdminId, success, reason);
                    return null;
                });
    }

    private void write(
            HttpServletRequest request,
            String email,
            @Nullable UUID superAdminId,
            boolean success,
            @Nullable FailureReason reason) {
        loginAudit.record(
                null,
                superAdminId,
                email,
                success,
                reason,
                ClientIpResolver.resolve(request),
                request.getHeader("User-Agent"));
    }
}
