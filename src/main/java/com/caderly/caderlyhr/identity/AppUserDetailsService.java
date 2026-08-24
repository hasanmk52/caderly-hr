package com.caderly.caderlyhr.identity;

import java.time.Clock;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Loads the authenticating user — <em>within the tenant resolved from the subdomain</em>.
 *
 * <p>That scoping is not implemented here and deliberately so. {@link AppUser} extends {@code
 * TenantAwareEntity}, so Hibernate's {@code @TenantId} restriction (ADR 0004) is already on the
 * {@code findByEmail} query. An account belonging to another tenant simply is not found, and
 * authentication fails identically to a typo'd address — satisfying the "valid email, wrong
 * subdomain must not leak that the account exists" requirement by construction rather than by
 * a hand-written predicate someone could later omit (ADR 0006 decision A).
 *
 * <p>This is why there is no custom {@code AuthenticationProvider} in this codebase despite PRD
 * §19.1 and the Implementation Plan calling for one: stock {@code DaoAuthenticationProvider} over
 * this service already has the property they were asking for.
 */
@Service
public class AppUserDetailsService implements UserDetailsService {

    private final AppUserRepository users;
    private final Clock clock;

    AppUserDetailsService(AppUserRepository users, Clock clock) {
        this.users = users;
        this.clock = clock;
    }

    @Override
    @Transactional(readOnly = true)
    public UserDetails loadUserByUsername(String email) {
        AppUser user =
                users.findByEmail(email)
                        .orElseThrow(() -> new UsernameNotFoundException("No user for the given email"));

        // An INVITED user has no password yet; passing null to the encoder would blow up, and an
        // empty hash can never match, so it fails as bad credentials like any other wrong password.
        String passwordHash = user.passwordHash() == null ? "" : user.passwordHash();

        return new AppUserPrincipal(
                user.requireId(),
                user.email(),
                passwordHash,
                user.roles(),
                isEnabled(user),
                !user.isLocked(clock.instant()));
    }

    /**
     * INVITED and DISABLED accounts cannot authenticate. LOCKED is deliberately not handled here —
     * it is expressed through {@code accountNonLocked} from the {@code locked_until} timestamp, so
     * a lapsed lock lets the user straight back in with no job to clear it.
     */
    private static boolean isEnabled(AppUser user) {
        return user.status() != UserStatus.INVITED && user.status() != UserStatus.DISABLED;
    }
}
