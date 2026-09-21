package com.caderly.caderlyhr.superadmin;

import com.caderly.caderlyhr.tenant.TenantContext;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

/**
 * Loads the authenticating Super Admin. Unlike {@code identity.AppUserDetailsService}, there is
 * no tenant to scope by — the Super Admin realm sits entirely outside {@code
 * TenantResolutionFilter} — but Global Constraint 1 still applies: {@code
 * TenantSessionVariableListener} fires on every transaction regardless of which table it touches
 * and throws unless {@code TenantContext} is either set or in system mode. {@code super_admin}
 * has no RLS at all, but the listener does not know that — it never inspects what the transaction
 * will touch — so this lookup still needs {@link TenantContext#runAsSystem}, exactly like every
 * other database call reachable from this realm.
 */
@Service
public class SuperAdminDetailsService implements UserDetailsService {

    private final SuperAdminRepository superAdmins;

    SuperAdminDetailsService(SuperAdminRepository superAdmins) {
        this.superAdmins = superAdmins;
    }

    @Override
    public UserDetails loadUserByUsername(String email) {
        SuperAdmin superAdmin =
                TenantContext.runAsSystem(
                        "resolve super admin by email for authentication",
                        () ->
                                superAdmins
                                        .findByEmail(email)
                                        .orElseThrow(
                                                () ->
                                                        new UsernameNotFoundException(
                                                                "No super admin for the given email")));

        return new SuperAdminPrincipal(
                superAdmin.requireId(), superAdmin.email(), superAdmin.passwordHash());
    }
}
