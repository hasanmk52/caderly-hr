package com.helyx.helyxhr.security;

import com.helyx.helyxhr.identity.AppUserPrincipal;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.session.SessionInformation;
import org.springframework.security.core.session.SessionRegistry;
import org.springframework.stereotype.Component;

/**
 * Immediately invalidates every session belonging to a user (PRD §19.1).
 *
 * <p>Called on password change and role change today; termination (BR-11, Phase 1.4) will reuse
 * it. Without this, a stolen session survives the password change made to shut it out.
 *
 * <p>Backed by the in-JVM {@code SessionRegistry}. When the deployment goes multi-instance and
 * sessions move to {@code spring-session-jdbc}, the registry becomes shared and this class does
 * not change (ADR 0006 decision C).
 */
@Component
public class SessionRevoker {

    private static final Logger log = LoggerFactory.getLogger(SessionRevoker.class);

    private final SessionRegistry sessionRegistry;

    SessionRevoker(SessionRegistry sessionRegistry) {
        this.sessionRegistry = sessionRegistry;
    }

    public void revokeAllFor(UUID userId) {
        int revoked = 0;
        // The registry is keyed by principal object, and AppUserPrincipal has no value equality,
        // so the only reliable lookup is a scan comparing user ids.
        for (Object principal : sessionRegistry.getAllPrincipals()) {
            if (!(principal instanceof AppUserPrincipal appUser) || !appUser.userId().equals(userId)) {
                continue;
            }
            for (SessionInformation session : sessionRegistry.getAllSessions(principal, false)) {
                session.expireNow();
                revoked++;
            }
        }
        if (revoked > 0) {
            log.info("Revoked {} session(s) for user {}", revoked, userId);
        }
    }
}
