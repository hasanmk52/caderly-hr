package com.caderly.caderlyhr.identity;

import com.caderly.caderlyhr.common.AuditActor;
import java.util.Collection;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

/**
 * The authenticated principal. Carries the user's id so downstream code can identify the actor
 * without another lookup by email — which matters because email is only unique per tenant (BR-12).
 *
 * <p>A detached snapshot, not the entity: it lives in the HTTP session, and holding a JPA entity
 * there would keep a detached instance alive across requests.
 *
 * <p>Implements {@link AuditActor} so {@code audit.EntityAuditListener} can attribute a write without
 * {@code audit} depending on {@code identity} (ADR 0017) — see that interface's Javadoc.
 *
 * <p>Not {@code final}, for exactly one subclass: {@link ImpersonatedAdminPrincipal}, which
 * overrides {@link #roleNames()} alone. It has to <em>be</em> an {@code AppUserPrincipal} rather
 * than wrap one, because every {@code @AuthenticationPrincipal AppUserPrincipal} controller
 * parameter in the application resolves by assignability — see that class's Javadoc. The
 * constructor stays package-private, so the set of subclasses cannot grow outside this package.
 */
public class AppUserPrincipal implements UserDetails, AuditActor {

    private final UUID userId;
    private final String email;
    private final String passwordHash;
    private final Set<Role> roles;
    private final boolean enabled;
    private final boolean accountNonLocked;

    AppUserPrincipal(
            UUID userId,
            String email,
            String passwordHash,
            Set<Role> roles,
            boolean enabled,
            boolean accountNonLocked) {
        this.userId = userId;
        this.email = email;
        this.passwordHash = passwordHash;
        this.roles = Set.copyOf(roles);
        this.enabled = enabled;
        this.accountNonLocked = accountNonLocked;
    }

    /**
     * Whether {@code user} may hold a session at all.
     *
     * <p>INVITED and DISABLED accounts cannot. LOCKED is deliberately not handled here — it is
     * expressed through {@code accountNonLocked} from the {@code locked_until} timestamp, so a
     * lapsed lock lets the user straight back in with no job to clear it.
     *
     * <p>Lives on the principal rather than in {@link AppUserDetailsService} because two callers
     * build principals now: that service on login, and {@link ImpersonationService} on a support
     * hand-off. A second copy of this predicate is exactly the kind of drift that ends with a
     * disabled account still reachable down one of the two paths.
     */
    static boolean isEnabled(AppUser user) {
        return user.status() != UserStatus.INVITED && user.status() != UserStatus.DISABLED;
    }

    public UUID userId() {
        return userId;
    }

    public Set<Role> roles() {
        return roles;
    }

    @Override
    public UUID actorId() {
        return userId;
    }

    @Override
    public Set<String> roleNames() {
        return roles.stream().map(Role::name).collect(Collectors.toUnmodifiableSet());
    }

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return roles.stream()
                .map(role -> (GrantedAuthority) new SimpleGrantedAuthority(role.authority()))
                .toList();
    }

    @Override
    public String getPassword() {
        return passwordHash;
    }

    @Override
    public String getUsername() {
        return email;
    }

    @Override
    public boolean isAccountNonExpired() {
        return true;
    }

    @Override
    public boolean isAccountNonLocked() {
        return accountNonLocked;
    }

    @Override
    public boolean isCredentialsNonExpired() {
        return true;
    }

    @Override
    public boolean isEnabled() {
        return enabled;
    }

    /** Never include the password hash. Guards against an accidental log or error message. */
    @Override
    public String toString() {
        return "AppUserPrincipal[userId=" + userId + ", email=" + email + ", roles=" + roles + "]";
    }
}
