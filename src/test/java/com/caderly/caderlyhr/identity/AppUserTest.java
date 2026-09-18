package com.caderly.caderlyhr.identity;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.junit.jupiter.api.Test;

/**
 * Pure logic: the lock/unlock state transitions and invite/reset state transitions. No Spring, no
 * database.
 *
 * <p>The failure-counting/window logic this class used to cover moved to {@code
 * LoginAttemptServiceTest} (ADR 0017): {@link AppUser}'s only remaining job around lockout is
 * holding the resulting {@code lockedUntil}/{@code status} state, not deciding when to set it.
 */
class AppUserTest {

    private static final Instant T0 = Instant.parse("2026-08-02T10:00:00Z");

    private static AppUser activeUser() {
        return AppUser.active("someone@example.test", "hash");
    }

    @Test
    void lock_setsLockedUntilAndStatus() {
        AppUser user = activeUser();

        user.lock(T0.plus(15, ChronoUnit.MINUTES));

        assertThat(user.status()).isEqualTo(UserStatus.LOCKED);
        assertThat(user.lockedUntil()).isEqualTo(T0.plus(15, ChronoUnit.MINUTES));
    }

    @Test
    void isLocked_beforeLockExpiry_returnsTrue() {
        AppUser user = activeUser();
        user.lock(T0.plus(15, ChronoUnit.MINUTES));

        assertThat(user.isLocked(T0.plus(14, ChronoUnit.MINUTES))).isTrue();
    }

    @Test
    void isLocked_afterLockHasElapsed_returnsFalse() {
        AppUser user = activeUser();
        user.lock(T0.plus(15, ChronoUnit.MINUTES));

        // Driven by the timestamp, not the status flag: no scheduled job clears a lapsed lock.
        assertThat(user.isLocked(T0.plus(16, ChronoUnit.MINUTES))).isFalse();
    }

    @Test
    void unlock_whenLocked_returnsUserToActiveAndClearsLockedUntil() {
        AppUser user = activeUser();
        user.lock(T0.plus(15, ChronoUnit.MINUTES));

        user.unlock();

        assertThat(user.status()).isEqualTo(UserStatus.ACTIVE);
        assertThat(user.lockedUntil()).isNull();
    }

    @Test
    void recordSuccessfulLogin_afterALock_clearsTheLockAndStampsLastLogin() {
        AppUser user = activeUser();
        user.lock(T0);

        user.recordSuccessfulLogin(T0.plusSeconds(2));

        assertThat(user.lastLoginAt()).isEqualTo(T0.plusSeconds(2));
        assertThat(user.isLocked(T0.plusSeconds(2))).isFalse();
        assertThat(user.status()).isEqualTo(UserStatus.ACTIVE);
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
    void revoke_whenRoleHeld_removesIt() {
        AppUser user = activeUser();
        user.grant(Role.MANAGER);

        user.revoke(Role.MANAGER);

        assertThat(user.roles()).doesNotContain(Role.MANAGER);
    }

    @Test
    void revoke_whenRoleNeverGranted_isNoOp() {
        AppUser user = activeUser();

        user.revoke(Role.MANAGER);

        assertThat(user.roles()).isEmpty();
    }

    @Test
    void issueIcalToken_whenNoneYet_setsToken() {
        AppUser user = activeUser();
        assertThat(user.icalToken()).isNull();

        user.issueIcalToken("raw-token-value");

        assertThat(user.icalToken()).isEqualTo("raw-token-value");
    }

    @Test
    void issueIcalToken_whenCalledAgain_overwritesThePreviousValue() {
        // Regeneration must invalidate the old URL immediately (Phase 1.8 DoD) — a plain
        // overwrite, since this is a column, not a history table.
        AppUser user = activeUser();
        user.issueIcalToken("first-token");

        user.issueIcalToken("second-token");

        assertThat(user.icalToken()).isEqualTo("second-token");
    }

    @Test
    void changePassword_whenAccountWasLocked_clearsTheLock() {
        // A successful reset is proof of mailbox control, so it should not leave the user
        // locked out by the failures that prompted the reset in the first place.
        AppUser user = activeUser();
        user.lock(T0.plus(15, ChronoUnit.MINUTES));

        user.changePassword("new-hash");

        assertThat(user.isLocked(T0)).isFalse();
        // The status column must follow the timestamp. Clearing one without the other left
        // accounts reading LOCKED forever while being perfectly usable.
        assertThat(user.status()).isEqualTo(UserStatus.ACTIVE);
    }
}
