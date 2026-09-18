package com.caderly.caderlyhr.security;

import com.caderly.caderlyhr.audit.LoginAuditService;
import com.caderly.caderlyhr.audit.system.LoginAudit.FailureReason;
import com.caderly.caderlyhr.common.ClientIpResolver;
import com.caderly.caderlyhr.identity.AppUser;
import com.caderly.caderlyhr.identity.AppUserPrincipal;
import com.caderly.caderlyhr.identity.AppUserRepository;
import com.caderly.caderlyhr.identity.LoginAttemptService;
import com.caderly.caderlyhr.tenant.TenantContext;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.context.event.EventListener;
import org.springframework.security.authentication.event.AbstractAuthenticationFailureEvent;
import org.springframework.security.authentication.event.AuthenticationFailureBadCredentialsEvent;
import org.springframework.security.authentication.event.AuthenticationFailureDisabledEvent;
import org.springframework.security.authentication.event.AuthenticationFailureLockedEvent;
import org.springframework.security.authentication.event.AuthenticationSuccessEvent;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * Bridges Spring Security's authentication events to both the login audit trail (FR-11.2) and the
 * lockout counter, in that order — the (email + IP) lockout re-key (ADR 0017) counts the {@code
 * login_audit} row this class just wrote for the current attempt, so writing the audit row first
 * and evaluating lockout second, in one method, keeps that ordering explicit rather than relying
 * on two independent {@code @EventListener} beans agreeing on an order Spring does not guarantee
 * between them.
 *
 * <p>Events rather than a custom {@code AuthenticationProvider} so this stays out of the
 * authentication path itself (ADR 0006 decision A). They are published synchronously on the
 * request thread, so {@code TenantContext} is still populated and every write here is
 * tenant-scoped like any other.
 */
@Component
class LoginAttemptListener {

    private final LoginAttemptService loginAttempts;
    private final LoginAuditService loginAudit;
    private final AppUserRepository users;

    LoginAttemptListener(
            LoginAttemptService loginAttempts, LoginAuditService loginAudit, AppUserRepository users) {
        this.loginAttempts = loginAttempts;
        this.loginAudit = loginAudit;
        this.users = users;
    }

    @EventListener
    void onSuccess(AuthenticationSuccessEvent event) {
        String email = event.getAuthentication().getName();
        UUID userId =
                event.getAuthentication().getPrincipal() instanceof AppUserPrincipal principal
                        ? principal.userId()
                        : null;
        loginAudit.record(
                TenantContext.get().orElse(null), userId, email, true, null, ip(), userAgent());
        loginAttempts.recordSuccess(email);
    }

    /**
     * Ambiguous by design: Spring Security's {@code hideUserNotFoundExceptions} (default {@code
     * true}) collapses "no such email" and "wrong password" into this one event, precisely so a
     * timing or event-type difference cannot leak which case occurred to an attacker. This method
     * does its own tenant-scoped lookup to tell the two apart for the audit row and the lockout
     * decision — never for the login page's response, which stays identical either way.
     */
    @EventListener
    void onBadCredentials(AuthenticationFailureBadCredentialsEvent event) {
        String email = event.getAuthentication().getName();
        UUID tenantId = TenantContext.get().orElse(null);
        String ip = ip();
        Optional<AppUser> user = users.findByEmail(email);

        FailureReason reason =
                user.isPresent() ? FailureReason.BAD_CREDENTIALS : FailureReason.UNKNOWN_EMAIL;
        UUID userId = user.map(AppUser::requireId).orElse(null);
        loginAudit.record(tenantId, userId, email, false, reason, ip, userAgent());

        // An unknown email has no account to lock, and locking on a hunch of who it "might" be
        // would turn the lockout mechanism into an account-existence oracle.
        if (user.isPresent() && tenantId != null) {
            loginAttempts.evaluateAfterFailure(tenantId, userId, email, ip);
        }
    }

    @EventListener
    void onLocked(AuthenticationFailureLockedEvent event) {
        recordKnownAccountFailure(event, FailureReason.ACCOUNT_LOCKED);
    }

    @EventListener
    void onDisabled(AuthenticationFailureDisabledEvent event) {
        recordKnownAccountFailure(event, FailureReason.ACCOUNT_DISABLED_OR_INVITED);
    }

    /**
     * The account is already locked or cannot authenticate at all (disabled or still invited) —
     * only the audit row is written. Running the lockout evaluation here too would either extend
     * an existing lock indefinitely for someone still knocking on a locked door, or attempt to
     * lock an account that already can't log in either way.
     */
    private void recordKnownAccountFailure(
            AbstractAuthenticationFailureEvent event, FailureReason reason) {
        String email = event.getAuthentication().getName();
        UUID userId = users.findByEmail(email).map(AppUser::requireId).orElse(null);
        loginAudit.record(TenantContext.get().orElse(null), userId, email, false, reason, ip(), userAgent());
    }

    private @Nullable String ip() {
        return RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attrs
                ? ClientIpResolver.resolve(attrs.getRequest())
                : null;
    }

    private @Nullable String userAgent() {
        return RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attrs
                ? attrs.getRequest().getHeader("User-Agent")
                : null;
    }
}
