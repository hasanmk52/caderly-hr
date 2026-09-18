package com.caderly.caderlyhr.identity;

import com.caderly.caderlyhr.audit.LoginAuditService;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Locks an account after repeated failures, keyed on (email + IP) via {@code
 * audit.LoginAuditService}'s per-attempt log (ADR 0017, superseding ADR 0006 decision B's
 * per-user-only counter — {@code login_audit} is exactly the record that decision was waiting on).
 *
 * <p>The <em>trigger</em> is scoped by (email, ip): five failures from one IP against one email
 * locks the account. The <em>result</em> is still "this account is locked" — Spring Security's
 * {@code UserDetails.isAccountNonLocked()} is inherently per-user, so that is the correct seam.
 * Four failures from one IP plus four from another no longer trips the lock, unlike the old
 * per-user counter; {@code security.RateLimitFilter}'s 10/min/IP limit is what still bounds a
 * single IP spraying many different accounts.
 */
@Service
public class LoginAttemptService {

    private static final Logger log = LoggerFactory.getLogger(LoginAttemptService.class);

    /** PRD §19.1: 5 failures inside this window trip the lock. */
    static final int MAX_FAILED_LOGINS = 5;

    static final Duration FAILED_LOGIN_WINDOW = Duration.ofMinutes(15);
    static final Duration LOCKOUT_DURATION = Duration.ofMinutes(15);

    private final AppUserRepository users;
    private final LoginAuditService loginAudit;
    private final Clock clock;

    LoginAttemptService(AppUserRepository users, LoginAuditService loginAudit, Clock clock) {
        this.users = users;
        this.loginAudit = loginAudit;
        this.clock = clock;
    }

    @Transactional
    public void recordSuccess(String email) {
        users.findByEmail(email)
                .ifPresent(
                        user -> {
                            user.recordSuccessfulLogin(clock.instant());
                            users.save(user);
                        });
    }

    /**
     * Counts this tenant's (email, ip) failures in the trailing window — including the
     * just-written {@code login_audit} row for the current attempt — and locks the account once
     * the count reaches {@link #MAX_FAILED_LOGINS}.
     *
     * <p>Only called for a <em>known</em> user (the caller has already resolved {@code userId});
     * an unknown email has no {@link AppUser} row to lock, and creating one just to lock it would
     * turn the lockout mechanism into an account-existence oracle.
     */
    @Transactional
    public void evaluateAfterFailure(UUID tenantId, UUID userId, String email, String ip) {
        Instant now = clock.instant();
        long failures = loginAudit.countRecentFailures(tenantId, email, ip, now.minus(FAILED_LOGIN_WINDOW));
        if (failures < MAX_FAILED_LOGINS) {
            return;
        }
        users.findById(userId)
                .ifPresent(
                        user -> {
                            user.lock(now.plus(LOCKOUT_DURATION));
                            users.save(user);
                            // Log the email and IP, never the attempted password (CLAUDE.md §6 A09).
                            log.warn(
                                    "Account {} locked until {} after {} failed attempts from {}",
                                    email,
                                    user.lockedUntil(),
                                    failures,
                                    ip);
                        });
    }
}
