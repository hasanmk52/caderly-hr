package com.caderly.caderlyhr.superadmin;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.GrantedAuthority;

class SuperAdminPrincipalTest {

    @Test
    void getAuthorities_isExactlyRoleSuperAdmin() {
        SuperAdminPrincipal principal =
                new SuperAdminPrincipal(UUID.randomUUID(), "root@caderly.test", "hash");

        assertThat(principal.getAuthorities())
                .extracting(GrantedAuthority::getAuthority)
                .containsExactly("ROLE_SUPER_ADMIN");
    }

    @Test
    void roleNames_isExactlySuperAdmin() {
        SuperAdminPrincipal principal =
                new SuperAdminPrincipal(UUID.randomUUID(), "root@caderly.test", "hash");

        assertThat(principal.roleNames()).containsExactlyInAnyOrder("SUPER_ADMIN");
    }

    @Test
    void isAlwaysEnabledAndNeverLocked_regardlessOfConstructorArgs() {
        SuperAdminPrincipal principal =
                new SuperAdminPrincipal(UUID.randomUUID(), "root@caderly.test", "hash");

        assertThat(principal.isEnabled()).isTrue();
        assertThat(principal.isAccountNonLocked()).isTrue();
        assertThat(principal.isAccountNonExpired()).isTrue();
        assertThat(principal.isCredentialsNonExpired()).isTrue();
    }

    @Test
    void toString_neverIncludesThePasswordHash() {
        SuperAdminPrincipal principal =
                new SuperAdminPrincipal(UUID.randomUUID(), "root@caderly.test", "super-secret-hash");

        assertThat(principal.toString()).doesNotContain("super-secret-hash");
    }
}
