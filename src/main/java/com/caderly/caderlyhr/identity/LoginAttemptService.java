package com.caderly.caderlyhr.identity;

import java.time.Clock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Maintains the failed-login counter behind the 5-in-15-minutes lockout (PRD §19.1).
 *
 * <p>Keyed on the user alone, not (email + IP) as the PRD specifies: the per-attempt record that
 * would carry an IP is {@code login_audit}, which Phase 1.11 owns. The IP axis is covered
 * meanwhile by the 10/min/IP login rate limit, a strictly tighter bound on any single address.
 * See ADR 0006 decision B.
 */
@Service
public class LoginAttemptService {

    private static final Logger log = LoggerFactory.getLogger(LoginAttemptService.class);

    private final AppUserRepository users;
    private final Clock clock;

    LoginAttemptService(AppUserRepository users, Clock clock) {
        this.users = users;
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
     * A failure against an address with no account is a no-op: there is nothing to count, and
     * creating a record would turn the counter itself into an account-existence oracle.
     */
    @Transactional
    public void recordFailure(String email) {
        users.findByEmail(email)
                .ifPresent(
                        user -> {
                            user.recordFailedLogin(clock.instant());
                            users.save(user);
                            if (user.isLocked(clock.instant())) {
                                // Log the email, never the attempted password (CLAUDE.md §6 A09).
                                log.warn(
                                        "Account {} locked until {} after {} failed attempts",
                                        email,
                                        user.lockedUntil(),
                                        user.failedLoginCount());
                            }
                        });
    }
}
