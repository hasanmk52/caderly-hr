package com.helyx.helyxhr.identity;

import com.helyx.helyxhr.common.ConflictException;
import com.helyx.helyxhr.common.SecureToken;
import com.helyx.helyxhr.common.ValidationException;
import com.helyx.helyxhr.notifications.EmailOutboxService;
import com.helyx.helyxhr.tenant.TenantContext;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Invites a user and redeems the resulting invite (PRD FR-1.4, US-ID.1). */
@Service
public class InviteService {

    private static final Logger log = LoggerFactory.getLogger(InviteService.class);
    private static final Duration INVITE_TTL = Duration.ofHours(24);

    private final AppUserRepository users;
    private final EmailOutboxService emailOutbox;
    private final PasswordEncoder passwordEncoder;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    InviteService(
            AppUserRepository users,
            EmailOutboxService emailOutbox,
            PasswordEncoder passwordEncoder,
            ApplicationEventPublisher events,
            Clock clock) {
        this.users = users;
        this.emailOutbox = emailOutbox;
        this.passwordEncoder = passwordEncoder;
        this.events = events;
        this.clock = clock;
    }

    /** Backs the admin user list — a service-layer read so it has its own transaction (CLAUDE.md §7: never on the controller). */
    @Transactional(readOnly = true)
    public List<AppUser> listUsers() {
        return users.findAllByOrderByEmailAsc();
    }

    /**
     * Creates an INVITED user and queues their invite email.
     *
     * <p>The enqueue happens in this same transaction (CLAUDE.md §6a rule 1), so the user and the
     * intent to tell them about it commit together. There is no window in which one exists
     * without the other.
     *
     * @param appBaseUrl origin the invite link points at, e.g. {@code https://mhz.helyx.app}.
     *     Passed in rather than derived here because only the web layer knows the request's scheme
     *     and host, and the link must land on this tenant's subdomain for login to work.
     */
    @Transactional
    public UUID invite(String email, Set<Role> roles, String appBaseUrl, String tenantName) {
        String normalisedEmail = normalise(email);
        if (roles.isEmpty()) {
            throw new ValidationException("USER_ROLES_REQUIRED", "Pick at least one role");
        }
        // Uniqueness is per tenant, not global (BR-12); this check is tenant-scoped for the
        // same reason every other query here is — @TenantId, not a predicate.
        if (users.existsByEmail(normalisedEmail)) {
            throw new ConflictException(
                    "USER_EMAIL_TAKEN", "A user with that email already exists in this workspace");
        }

        String rawToken = SecureToken.generate();
        AppUser user =
                AppUser.invited(
                        normalisedEmail, SecureToken.hash(rawToken), clock.instant().plus(INVITE_TTL));
        roles.forEach(user::grant);
        AppUser saved = users.save(user);

        emailOutbox.enqueue(
                TenantContext.require(),
                normalisedEmail,
                IdentityEmails.inviteSubject(tenantName),
                IdentityEmails.inviteBody(tenantName, acceptUrl(appBaseUrl, rawToken)));

        // The token is never logged — it is a live credential until redeemed.
        log.info("Invited {} with roles {}", normalisedEmail, roles);
        return saved.requireId();
    }

    /**
     * Redeems an invite: verifies the token, sets the password, and activates the account.
     *
     * <p>Lookup is by hash, so the raw token is compared only against stored digests.
     */
    @Transactional
    public void acceptInvite(String rawToken, String newPassword) {
        String tokenHash = SecureToken.hash(rawToken);
        Instant now = clock.instant();

        AppUser user =
                users.findByInviteTokenHash(tokenHash)
                        .filter(candidate -> candidate.hasValidInvite(now))
                        .orElseThrow(
                                () ->
                                        // One message for "no such token", "already used", and "expired".
                                        // Distinguishing them would confirm that a token once existed.
                                        new ValidationException(
                                                "INVITE_TOKEN_INVALID",
                                                "This invite link is invalid or has expired. Ask an administrator"
                                                        + " to send a new one."));

        user.acceptInvite(passwordEncoder.encode(newPassword));
        users.save(user);

        // Fired after commit by the listener (mirrors PasswordChangedEvent), so a rolled-back
        // accept never activates an Employee row for a user that isn't really active.
        events.publishEvent(new UserInviteAcceptedEvent(user.requireId()));
        log.info("Invite accepted for {}", user.email());
    }

    /** Emails are matched case-insensitively; storing them lower-cased makes that the default. */
    private static String normalise(String email) {
        return email.trim().toLowerCase(Locale.ROOT);
    }

    private static String acceptUrl(String appBaseUrl, String rawToken) {
        return appBaseUrl
                + "/accept-invite?token="
                + URLEncoder.encode(rawToken, StandardCharsets.UTF_8);
    }
}
