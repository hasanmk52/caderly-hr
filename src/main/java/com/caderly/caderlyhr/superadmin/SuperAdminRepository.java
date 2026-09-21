package com.caderly.caderlyhr.superadmin;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * No {@code TenantAwareRepository} here (unlike {@code identity.AppUserRepository}): {@link
 * SuperAdmin} carries no {@code @TenantId}, so a plain {@link JpaRepository} is correct — exactly
 * the choice {@code tenant.TenantRepository} already makes for the other cross-tenant table.
 */
public interface SuperAdminRepository extends JpaRepository<SuperAdmin, UUID> {

    Optional<SuperAdmin> findByEmail(String email);
}
