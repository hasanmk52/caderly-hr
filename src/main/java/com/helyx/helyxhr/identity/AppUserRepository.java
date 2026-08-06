package com.helyx.helyxhr.identity;

import com.helyx.helyxhr.common.TenantAwareRepository;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Repository;

/**
 * No method here mentions tenant_id, and none may (CLAUDE.md §5 rule 4). {@code findByEmail} is
 * already restricted to the tenant resolved from the subdomain because {@link AppUser} carries
 * {@code @TenantId} — which is exactly what makes "valid email, wrong subdomain" fail as
 * user-not-found with no security-specific code (ADR 0006 decision A).
 */
@Repository
public interface AppUserRepository extends TenantAwareRepository<AppUser> {

    Optional<AppUser> findByEmail(String email);

    boolean existsByEmail(String email);

    /** Invite redemption looks the user up by stored digest; the raw token is never persisted. */
    Optional<AppUser> findByInviteTokenHash(String inviteTokenHash);

    List<AppUser> findAllByOrderByEmailAsc();
}
