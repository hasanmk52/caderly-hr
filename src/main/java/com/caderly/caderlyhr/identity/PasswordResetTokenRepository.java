package com.caderly.caderlyhr.identity;

import com.caderly.caderlyhr.common.TenantAwareRepository;
import java.util.Optional;
import org.springframework.stereotype.Repository;

@Repository
public interface PasswordResetTokenRepository extends TenantAwareRepository<PasswordResetToken> {

    /**
     * Looked up by hash alone: the tenant comes from the subdomain and {@code @TenantId} scopes the
     * row, so a token minted for tenant A is invisible on tenant B's subdomain.
     */
    Optional<PasswordResetToken> findByTokenHash(String tokenHash);
}
