package com.caderly.caderlyhr.security;

import com.caderly.caderlyhr.identity.LoginAttemptService;
import org.springframework.context.event.EventListener;
import org.springframework.security.authentication.event.AuthenticationFailureBadCredentialsEvent;
import org.springframework.security.authentication.event.AuthenticationSuccessEvent;
import org.springframework.stereotype.Component;

/**
 * Bridges Spring Security's authentication events to the lockout counter.
 *
 * <p>Events rather than a custom {@code AuthenticationProvider} so the counting stays out of the
 * authentication path itself. They are published synchronously on the request thread, so {@code
 * TenantContext} is still populated and the counter update is tenant-scoped like any other write.
 */
@Component
class LoginAttemptListener {

    private final LoginAttemptService loginAttempts;

    LoginAttemptListener(LoginAttemptService loginAttempts) {
        this.loginAttempts = loginAttempts;
    }

    @EventListener
    void onSuccess(AuthenticationSuccessEvent event) {
        loginAttempts.recordSuccess(event.getAuthentication().getName());
    }

    /**
     * Deliberately narrower than {@code AbstractAuthenticationFailureEvent}: only a wrong password
     * counts toward the lock. Counting the locked and disabled failures too would let an attacker
     * extend an existing lock indefinitely just by continuing to knock.
     */
    @EventListener
    void onBadCredentials(AuthenticationFailureBadCredentialsEvent event) {
        loginAttempts.recordFailure(event.getAuthentication().getName());
    }
}
