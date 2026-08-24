package com.caderly.caderlyhr.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.caderly.caderlyhr.common.ConflictException;
import com.caderly.caderlyhr.common.ValidationException;
import com.caderly.caderlyhr.notifications.system.EmailOutbox;
import com.caderly.caderlyhr.notifications.system.EmailOutboxRepository;
import com.caderly.caderlyhr.support.MutableClock;
import com.caderly.caderlyhr.support.MutableClockConfiguration;
import com.caderly.caderlyhr.tenant.TenantContext;
import com.caderly.caderlyhr.tenantisolation.TenantIsolationTestBase;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.security.crypto.password.PasswordEncoder;

/** Invite and password-reset flows end to end, including token discipline (PRD §19.1). */
@Import(MutableClockConfiguration.class)
class InviteAndResetServiceTest extends TenantIsolationTestBase {

    private static final String BASE_URL = "https://acme.localhost";
    private static final String TENANT_NAME = "Acme";
    private static final String GOOD_PASSWORD = "Str0ngPassphrase";

    private static final Pattern TOKEN_IN_LINK = Pattern.compile("[?&]token=([A-Za-z0-9_%\\-]+)");

    @Autowired private InviteService invites;
    @Autowired private PasswordResetService resets;
    @Autowired private AppUserRepository users;
    @Autowired private EmailOutboxRepository outbox;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private MutableClock clock;

    private String uniqueEmail() {
        return "user-" + UUID.randomUUID() + "@example.test";
    }

    @Test
    void invite_createsInvitedUserWithRolesAndQueuesEmailInTheSameTransaction() {
        String email = uniqueEmail();

        UUID userId =
                asTenant(
                        tenantA,
                        () -> invites.invite(email, Set.of(Role.ADMIN), BASE_URL, TENANT_NAME));

        AppUser created = asTenant(tenantA, () -> users.findById(userId)).orElseThrow();
        assertThat(created.status()).isEqualTo(UserStatus.INVITED);
        assertThat(created.roles()).containsExactly(Role.ADMIN);
        assertThat(created.passwordHash()).isNull();

        EmailOutbox queued = lastEmailTo(email);
        assertThat(queued.subject()).contains(TENANT_NAME);
        assertThat(queued.bodyHtml()).contains(BASE_URL + "/accept-invite?token=");
    }

    @Test
    void invite_storesOnlyTheTokenHashNeverTheRawToken() {
        // CLAUDE.md §6 A02: the raw token lives in the email and nowhere else, so a database
        // leak yields nothing redeemable.
        String email = uniqueEmail();
        UUID userId =
                asTenant(
                        tenantA,
                        () -> invites.invite(email, Set.of(Role.EMPLOYEE), BASE_URL, TENANT_NAME));

        String rawToken = tokenFromLastEmailTo(email);
        AppUser created = asTenant(tenantA, () -> users.findById(userId)).orElseThrow();

        assertThat(created.inviteTokenHash()).isNotNull().isNotEqualTo(rawToken);
        assertThat(created.inviteTokenHash()).hasSize(64); // SHA-256 hex
    }

    @Test
    void invite_whenEmailAlreadyExistsInThisTenant_throwsConflict() {
        String email = uniqueEmail();
        asTenant(tenantA, () -> invites.invite(email, Set.of(Role.EMPLOYEE), BASE_URL, TENANT_NAME));

        assertThatThrownBy(
                        () ->
                                asTenant(
                                        tenantA,
                                        () -> invites.invite(email, Set.of(Role.EMPLOYEE), BASE_URL, TENANT_NAME)))
                .isInstanceOf(ConflictException.class);
    }

    @Test
    void invite_whenSameEmailExistsInAnotherTenant_succeeds() {
        // BR-12: email is unique per tenant, not globally. Two companies may employ the same
        // person, and neither should be able to detect the other.
        String email = uniqueEmail();
        asTenant(tenantA, () -> invites.invite(email, Set.of(Role.EMPLOYEE), BASE_URL, TENANT_NAME));

        UUID inTenantB =
                asTenant(
                        tenantB,
                        () -> invites.invite(email, Set.of(Role.EMPLOYEE), BASE_URL, "Other Co"));

        assertThat(inTenantB).isNotNull();
        assertThat(asTenant(tenantB, () -> users.findById(inTenantB))).isPresent();
    }

    @Test
    void invite_withNoRoles_isRejected() {
        assertThatThrownBy(
                        () ->
                                asTenant(
                                        tenantA,
                                        () -> invites.invite(uniqueEmail(), Set.of(), BASE_URL, TENANT_NAME)))
                .isInstanceOf(ValidationException.class);
    }

    @Test
    void acceptInvite_withValidToken_activatesUserAndSetsBcryptPassword() {
        String email = uniqueEmail();
        UUID userId =
                asTenant(
                        tenantA,
                        () -> invites.invite(email, Set.of(Role.EMPLOYEE), BASE_URL, TENANT_NAME));
        String rawToken = tokenFromLastEmailTo(email);

        asTenant(tenantA, () -> invites.acceptInvite(rawToken, GOOD_PASSWORD));

        AppUser activated = asTenant(tenantA, () -> users.findById(userId)).orElseThrow();
        assertThat(activated.status()).isEqualTo(UserStatus.ACTIVE);
        assertThat(activated.inviteTokenHash()).isNull();
        assertThat(passwordEncoder.matches(GOOD_PASSWORD, activated.passwordHash())).isTrue();
        // BCrypt cost 12 (CLAUDE.md §6 A02) — the $2a$12$ prefix encodes the work factor.
        assertThat(activated.passwordHash()).startsWith("$2a$12$");
    }

    @Test
    void acceptInvite_whenReplayedWithTheSameToken_isRejected() {
        String email = uniqueEmail();
        asTenant(tenantA, () -> invites.invite(email, Set.of(Role.EMPLOYEE), BASE_URL, TENANT_NAME));
        String rawToken = tokenFromLastEmailTo(email);
        asTenant(tenantA, () -> invites.acceptInvite(rawToken, GOOD_PASSWORD));

        assertThatThrownBy(
                        () -> asTenant(tenantA, () -> invites.acceptInvite(rawToken, "An0therPassphrase")))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("invalid or has expired");
    }

    @Test
    void acceptInvite_afterTwentyFourHours_isRejected() {
        String email = uniqueEmail();
        asTenant(tenantA, () -> invites.invite(email, Set.of(Role.EMPLOYEE), BASE_URL, TENANT_NAME));
        String rawToken = tokenFromLastEmailTo(email);

        clock.advance(Duration.ofHours(25));

        assertThatThrownBy(() -> asTenant(tenantA, () -> invites.acceptInvite(rawToken, GOOD_PASSWORD)))
                .isInstanceOf(ValidationException.class);
    }

    @Test
    void acceptInvite_withAnUnknownToken_isRejected() {
        assertThatThrownBy(
                        () -> asTenant(tenantA, () -> invites.acceptInvite("not-a-real-token", GOOD_PASSWORD)))
                .isInstanceOf(ValidationException.class);
    }

    @Test
    void requestReset_forKnownAddress_queuesAResetEmail() {
        String email = activeUser();

        asTenant(tenantA, () -> resets.requestReset(email, BASE_URL, TENANT_NAME));

        EmailOutbox queued = lastEmailTo(email);
        assertThat(queued.subject()).contains("Reset your");
        assertThat(queued.bodyHtml()).contains(BASE_URL + "/reset-password?token=");
    }

    @Test
    void requestReset_forUnknownAddress_isSilentAndQueuesNothing() {
        // Enumeration safety (ADR 0006 decision E): no exception, no different return value,
        // and nothing an observer of the outbox could correlate.
        String unknown = uniqueEmail();

        asTenant(tenantA, () -> resets.requestReset(unknown, BASE_URL, TENANT_NAME));

        assertThat(emailsTo(unknown)).isEmpty();
    }

    @Test
    void completeReset_withValidToken_changesThePasswordAndConsumesTheToken() {
        String email = activeUser();
        asTenant(tenantA, () -> resets.requestReset(email, BASE_URL, TENANT_NAME));
        String rawToken = tokenFromLastEmailTo(email);

        asTenant(tenantA, () -> resets.completeReset(rawToken, "Rec0veredPassphrase"));

        AppUser updated = asTenant(tenantA, () -> users.findByEmail(email)).orElseThrow();
        assertThat(passwordEncoder.matches("Rec0veredPassphrase", updated.passwordHash())).isTrue();
        assertThat(asTenant(tenantA, () -> resets.isTokenRedeemable(rawToken))).isFalse();
    }

    @Test
    void completeReset_whenReplayed_isRejected() {
        String email = activeUser();
        asTenant(tenantA, () -> resets.requestReset(email, BASE_URL, TENANT_NAME));
        String rawToken = tokenFromLastEmailTo(email);
        asTenant(tenantA, () -> resets.completeReset(rawToken, "Rec0veredPassphrase"));

        assertThatThrownBy(
                        () -> asTenant(tenantA, () -> resets.completeReset(rawToken, "Y3tAnotherPassphrase")))
                .isInstanceOf(ValidationException.class);
    }

    @Test
    void completeReset_afterTwentyFourHours_isRejected() {
        String email = activeUser();
        asTenant(tenantA, () -> resets.requestReset(email, BASE_URL, TENANT_NAME));
        String rawToken = tokenFromLastEmailTo(email);

        clock.advance(Duration.ofHours(25));

        assertThatThrownBy(
                        () -> asTenant(tenantA, () -> resets.completeReset(rawToken, "Rec0veredPassphrase")))
                .isInstanceOf(ValidationException.class);
    }

    @Test
    void completeReset_clearsAnExistingLockout() {
        // A successful reset proves mailbox control, so it should not leave the user locked out
        // by the failed attempts that prompted the reset.
        String email = activeUser();
        asTenant(
                tenantA,
                () -> {
                    AppUser user = users.findByEmail(email).orElseThrow();
                    for (int i = 0; i < 5; i++) {
                        user.recordFailedLogin(clock.instant());
                    }
                    return users.save(user);
                });
        assertThat(asTenant(tenantA, () -> users.findByEmail(email).orElseThrow().isLocked(clock.instant())))
                .isTrue();

        asTenant(tenantA, () -> resets.requestReset(email, BASE_URL, TENANT_NAME));
        String rawToken = tokenFromLastEmailTo(email);
        asTenant(tenantA, () -> resets.completeReset(rawToken, "Rec0veredPassphrase"));

        AppUser recovered = asTenant(tenantA, () -> users.findByEmail(email)).orElseThrow();
        assertThat(recovered.isLocked(clock.instant())).isFalse();
        assertThat(recovered.status()).isEqualTo(UserStatus.ACTIVE);
    }

    @Test
    void resetToken_mintedInOneTenant_isInvisibleInAnother() {
        String email = activeUser();
        asTenant(tenantA, () -> resets.requestReset(email, BASE_URL, TENANT_NAME));
        String rawToken = tokenFromLastEmailTo(email);

        assertThat(asTenant(tenantB, () -> resets.isTokenRedeemable(rawToken))).isFalse();
        assertThat(asTenant(tenantA, () -> resets.isTokenRedeemable(rawToken))).isTrue();
    }

    /** Seeds an ACTIVE user in tenant A by inviting and immediately accepting. */
    private String activeUser() {
        String email = uniqueEmail();
        asTenant(tenantA, () -> invites.invite(email, Set.of(Role.EMPLOYEE), BASE_URL, TENANT_NAME));
        asTenant(tenantA, () -> invites.acceptInvite(tokenFromLastEmailTo(email), GOOD_PASSWORD));
        return email;
    }

    /** email_outbox is system-scoped, so reading it needs system mode rather than a tenant. */
    private List<EmailOutbox> emailsTo(String email) {
        return TenantContext.runAsSystem(
                "test: read outbox",
                () -> outbox.findAll().stream().filter(row -> row.toEmail().equals(email)).toList());
    }

    private EmailOutbox lastEmailTo(String email) {
        List<EmailOutbox> found = emailsTo(email);
        assertThat(found).as("queued emails to %s", email).isNotEmpty();
        return found.getLast();
    }

    /** Pulls the raw token back out of the delivered link, the way a real recipient would. */
    private String tokenFromLastEmailTo(String email) {
        Matcher matcher = TOKEN_IN_LINK.matcher(lastEmailTo(email).bodyHtml());
        assertThat(matcher.find()).as("token in email body").isTrue();
        return URLDecoder.decode(matcher.group(1), StandardCharsets.UTF_8);
    }
}
