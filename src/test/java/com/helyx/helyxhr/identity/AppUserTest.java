package com.helyx.helyxhr.identity;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.junit.jupiter.api.Test;

/** Pure logic: the lockout window and invite/reset state transitions. No Spring, no database. */
class AppUserTest {

    private static final Instant T0 = Instant.parse("2026-08-02T10:00:00Z");

    private static AppUser activeUser() {
        return AppUser.active("someone@example.test", "hash");
    }

    @Test
    void recordFailedLogin_whenFourFailuresInWindow_doesNotLock() {
        AppUser user = activeUser();

        for (int i = 0; i < 4; i++) {
            user.recordFailedLogin(T0.plusSeconds(i));
        }

        assertThat(user.failedLoginCount()).isEqualTo(4);
        assertThat(user.isLocked(T0.plusSeconds(5))).isFalse();
        assertThat(user.status()).isEqualTo(UserStatus.ACTIVE);
    }

    @Test
    void recordFailedLogin_whenFifthFailureInWindow_locksForFifteenMinutes() {
        AppUser user = activeUser();

        for (int i = 0; i < 5; i++) {
            user.recordFailedLogin(T0.plusSeconds(i));
        }

        assertThat(user.status()).isEqualTo(UserStatus.LOCKED);
        assertThat(user.lockedUntil()).isEqualTo(T0.plusSeconds(4).plus(15, ChronoUnit.MINUTES));
        assertThat(user.isLocked(T0.plus(14, ChronoUnit.MINUTES))).isTrue();
    }

    @Test
    void isLocked_whenLockHasElapsed_returnsFalse() {
        AppUser user = activeUser();
        for (int i = 0; i < 5; i++) {
            user.recordFailedLogin(T0);
        }

        // Driven by the timestamp, not the status flag: no scheduled job clears a lapsed lock.
        assertThat(user.isLocked(T0.plus(16, ChronoUnit.MINUTES))).isFalse();
    }

    @Test
    void recordFailedLogin_whenFailuresSpanMoreThanWindow_startsFreshWindowAndDoesNotLock() {
        // The reason failed_login_window_start exists (ADR 0006 decision B): five typos spread
        // over hours are not an attack, and PRD §19.1 says "5 failures / 15 min", not "5 ever".
        AppUser user = activeUser();

        for (int i = 0; i < 4; i++) {
            user.recordFailedLogin(T0.plus(i * 20L, ChronoUnit.MINUTES));
        }
        user.recordFailedLogin(T0.plus(80, ChronoUnit.MINUTES));

        assertThat(user.failedLoginCount()).isEqualTo(1);
        assertThat(user.isLocked(T0.plus(80, ChronoUnit.MINUTES))).isFalse();
    }

    @Test
    void recordSuccessfulLogin_afterSomeFailures_resetsCounterAndStampsLastLogin() {
        AppUser user = activeUser();
        user.recordFailedLogin(T0);
        user.recordFailedLogin(T0.plusSeconds(1));

        user.recordSuccessfulLogin(T0.plusSeconds(2));

        assertThat(user.failedLoginCount()).isZero();
        assertThat(user.lastLoginAt()).isEqualTo(T0.plusSeconds(2));
        assertThat(user.isLocked(T0.plusSeconds(2))).isFalse();
    }

    @Test
    void unlock_whenLocked_returnsUserToActive() {
        AppUser user = activeUser();
        for (int i = 0; i < 5; i++) {
            user.recordFailedLogin(T0);
        }

        user.unlock();

        assertThat(user.status()).isEqualTo(UserStatus.ACTIVE);
        assertThat(user.lockedUntil()).isNull();
        assertThat(user.failedLoginCount()).isZero();
    }

    @Test
    void acceptInvite_whenInvited_activatesAndClearsTokenSoItCannotBeReplayed() {
        AppUser user =
                AppUser.invited("new@example.test", "token-hash", T0.plus(24, ChronoUnit.HOURS));
        assertThat(user.hasValidInvite(T0)).isTrue();

        user.acceptInvite("bcrypt-hash");

        assertThat(user.status()).isEqualTo(UserStatus.ACTIVE);
        assertThat(user.passwordHash()).isEqualTo("bcrypt-hash");
        assertThat(user.inviteTokenHash()).isNull();
        assertThat(user.hasValidInvite(T0)).isFalse();
    }

    @Test
    void hasValidInvite_whenPastExpiry_returnsFalse() {
        AppUser user =
                AppUser.invited("new@example.test", "token-hash", T0.plus(24, ChronoUnit.HOURS));

        assertThat(user.hasValidInvite(T0.plus(25, ChronoUnit.HOURS))).isFalse();
    }

    @Test
    void grant_whenRoleAlreadyHeld_doesNotDuplicate() {
        AppUser user = activeUser();

        user.grant(Role.ADMIN);
        user.grant(Role.ADMIN);
        user.grant(Role.EMPLOYEE);

        assertThat(user.roles()).containsExactlyInAnyOrder(Role.ADMIN, Role.EMPLOYEE);
    }

    @Test
    void changePassword_whenAccountWasLocked_clearsTheLock() {
        // A successful reset is proof of mailbox control, so it should not leave the user
        // locked out by the failures that prompted the reset in the first place.
        AppUser user = activeUser();
        for (int i = 0; i < 5; i++) {
            user.recordFailedLogin(T0);
        }

        user.changePassword("new-hash");

        assertThat(user.isLocked(T0)).isFalse();
        assertThat(user.failedLoginCount()).isZero();
        // The status column must follow the timestamp. Clearing one without the other left
        // accounts reading LOCKED forever while being perfectly usable.
        assertThat(user.status()).isEqualTo(UserStatus.ACTIVE);
    }
}
