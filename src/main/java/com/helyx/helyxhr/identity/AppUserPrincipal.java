package com.helyx.helyxhr.identity;

import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

/**
 * The authenticated principal. Carries the user's id so downstream code can identify the actor
 * without another lookup by email — which matters because email is only unique per tenant (BR-12).
 *
 * <p>A detached snapshot, not the entity: it lives in the HTTP session, and holding a JPA entity
 * there would keep a detached instance alive across requests.
 */
public final class AppUserPrincipal implements UserDetails {

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

    public UUID userId() {
        return userId;
    }

    public Set<Role> roles() {
        return roles;
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

    /** Convenience for tests and for building an Authentication without the full entity. */
    public static AppUserPrincipal of(UUID userId, String email, Role... roles) {
        return new AppUserPrincipal(userId, email, "", Set.copyOf(List.of(roles)), true, true);
    }
}
