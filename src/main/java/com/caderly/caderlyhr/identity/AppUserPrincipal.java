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
 */
public final class AppUserPrincipal implements UserDetails, AuditActor {

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
