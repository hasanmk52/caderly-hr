package com.helyx.helyxhr.identity;

import com.helyx.helyxhr.common.SecureToken;
import com.helyx.helyxhr.common.ValidationException;
import com.helyx.helyxhr.notifications.EmailOutboxService;
import com.helyx.helyxhr.tenant.TenantContext;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Forgotten-password recovery (PRD FR-1.3, US-ID.2). */
@Service
public class PasswordResetService {

    private static final Logger log = LoggerFactory.getLogger(PasswordResetService.class);
    private static final Duration RESET_TTL = Duration.ofHours(24);

    private final AppUserRepository users;
    private final PasswordResetTokenRepository resetTokens;
    private final EmailOutboxService emailOutbox;
    private final PasswordEncoder passwordEncoder;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    PasswordResetService(
            AppUserRepository users,
            PasswordResetTokenRepository resetTokens,
            EmailOutboxService emailOutbox,
            PasswordEncoder passwordEncoder,
            ApplicationEventPublisher events,
            Clock clock) {
        this.users = users;
        this.resetTokens = resetTokens;
        this.emailOutbox = emailOutbox;
        this.passwordEncoder = passwordEncoder;
        this.events = events;
        this.clock = clock;
    }

    /**
     * Issues a reset token if the address belongs to a user here, and does nothing if it does not.
     *
     * <p>Returns {@code void} deliberately. Any signal at all — a boolean, an exception, a
     * different redirect — turns this endpoint into an account-existence oracle, and per BR-12 the
     * same address may legitimately exist in a different tenant.
     *
     * <p>The caveat, stated rather than hidden: responses are identical in <em>shape</em>, not in
     * <em>timing</em>. The existing-user path does extra database and enqueue work, so a careful
     * attacker with clean measurements can still distinguish the two. The 3/hour/email rate limit
     * (PRD §19.7) is what makes that impractical to exploit at scale. Equalising timing would mean
     * performing dummy work on the miss path; see ADR 0006 decision E.
     */
    @Transactional
    public void requestReset(String email, String appBaseUrl, String tenantName) {
        String normalisedEmail = normalise(email);
        users.findByEmail(normalisedEmail)
                .ifPresentOrElse(
                        user -> issueToken(user, normalisedEmail, appBaseUrl, tenantName),
                        () ->
                                log.info(
                                        "Password reset requested for unknown address; responding identically"));
    }

    private void issueToken(AppUser user, String email, String appBaseUrl, String tenantName) {
        String rawToken = SecureToken.generate();
        resetTokens.save(
                new PasswordResetToken(
                        user, SecureToken.hash(rawToken), clock.instant().plus(RESET_TTL)));

        emailOutbox.enqueue(
                TenantContext.require(),
                email,
                IdentityEmails.resetSubject(tenantName),
                IdentityEmails.resetBody(tenantName, resetUrl(appBaseUrl, rawToken)));

        log.info("Password reset token issued for {}", email);
    }

    /** Whether a token can still be redeemed — lets the reset page fail early on a dead link. */
    @Transactional(readOnly = true)
    public boolean isTokenRedeemable(String rawToken) {
        return resetTokens
                .findByTokenHash(SecureToken.hash(rawToken))
                .filter(token -> token.isRedeemable(clock.instant()))
                .isPresent();
    }

    /**
     * Consumes the token and sets the new password.
     *
     * <p>Stamping {@code used_at} is what makes the link single-use (PRD §19.1); the row is kept
     * rather than deleted so a replay is distinguishable from a token that never existed when
     * auditing lands in Phase 1.11.
     */
    @Transactional
    public void completeReset(String rawToken, String newPassword) {
        Instant now = clock.instant();
        PasswordResetToken token =
                resetTokens
                        .findByTokenHash(SecureToken.hash(rawToken))
                        .filter(candidate -> candidate.isRedeemable(now))
                        .orElseThrow(
                                () ->
                                        new ValidationException(
                                                "RESET_TOKEN_INVALID",
                                                "This reset link is invalid or has expired. Request a new one."));

        token.markUsed(now);
        resetTokens.save(token);

        AppUser user = token.user();
        user.changePassword(passwordEncoder.encode(newPassword));
        users.save(user);

        UUID userId = user.getId();
        if (userId == null) {
            throw new IllegalStateException("Reset token points at an unsaved user");
        }
        // Fired after commit by the listener, so a rolled-back reset does not log anyone out.
        events.publishEvent(new PasswordChangedEvent(userId));
        log.info("Password reset completed for {}", user.email());
    }

    private static String normalise(String email) {
        return email.trim().toLowerCase(Locale.ROOT);
    }

    private static String resetUrl(String appBaseUrl, String rawToken) {
        return appBaseUrl
                + "/reset-password?token="
                + URLEncoder.encode(rawToken, StandardCharsets.UTF_8);
    }
}
