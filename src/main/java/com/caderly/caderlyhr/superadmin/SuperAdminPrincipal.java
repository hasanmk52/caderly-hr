package com.caderly.caderlyhr.superadmin;

import com.caderly.caderlyhr.common.AuditActor;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

/**
 * The authenticated Super Admin principal. Simpler than {@code identity.AppUserPrincipal}: a
 * {@link SuperAdmin} row that exists has no invite/lock/disable lifecycle to reflect, so this is
 * always enabled and never locked, and there is exactly one role.
 *
 * <p>Implements {@link AuditActor} for the same reason {@code AppUserPrincipal} does — so {@code
 * audit.EntityAuditListener} can attribute a write without {@code audit} depending on this
 * package.
 */
public final class SuperAdminPrincipal implements UserDetails, AuditActor {

    private final UUID superAdminId;
    private final String email;
    private final String passwordHash;

    SuperAdminPrincipal(UUID superAdminId, String email, String passwordHash) {
        this.superAdminId = superAdminId;
        this.email = email;
        this.passwordHash = passwordHash;
    }

    public UUID superAdminId() {
        return superAdminId;
    }

    @Override
    public UUID actorId() {
        return superAdminId;
    }

    @Override
    public Set<String> roleNames() {
        return Set.of("SUPER_ADMIN");
    }

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return List.of(new SimpleGrantedAuthority("ROLE_SUPER_ADMIN"));
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
        return true;
    }

    @Override
    public boolean isCredentialsNonExpired() {
        return true;
    }

    @Override
    public boolean isEnabled() {
        return true;
    }

    /** Never include the password hash. Guards against an accidental log or error message. */
    @Override
    public String toString() {
        return "SuperAdminPrincipal[superAdminId=" + superAdminId + ", email=" + email + "]";
    }
}
