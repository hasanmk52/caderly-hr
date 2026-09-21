package com.caderly.caderlyhr.superadmin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.caderly.caderlyhr.TestcontainersConfiguration;
import com.caderly.caderlyhr.tenant.TenantContext;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;

/**
 * Every call here runs from a thread with no {@code TenantContext} set — exactly the Super Admin
 * realm's situation (Global Constraint 1) — so seeding a fixture row must go through {@link
 * TenantContext#runAsSystem} exactly like production code, matching {@code
 * TenantServiceTest}'s pattern.
 */
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("test")
@SpringBootTest
class SuperAdminDetailsServiceTest {

    @Autowired private SuperAdminDetailsService detailsService;
    @Autowired private SuperAdminRepository superAdmins;
    @Autowired private PasswordEncoder passwordEncoder;

    @AfterEach
    void clearTenantContext() {
        TenantContext.clear();
    }

    @Test
    void loadUserByUsername_knownEmail_returnsAMatchingPrincipal() {
        String email = uniqueEmail();
        seed(email, "s3cret-password");

        UserDetails loaded = detailsService.loadUserByUsername(email);

        assertThat(loaded).isInstanceOf(SuperAdminPrincipal.class);
        assertThat(loaded.getUsername()).isEqualTo(email);
        assertThat(passwordEncoder.matches("s3cret-password", loaded.getPassword())).isTrue();
        assertThat(loaded.getAuthorities()).extracting(Object::toString).containsExactly("ROLE_SUPER_ADMIN");
    }

    @Test
    void loadUserByUsername_unknownEmail_throwsUsernameNotFound() {
        assertThatThrownBy(() -> detailsService.loadUserByUsername(uniqueEmail()))
                .isInstanceOf(UsernameNotFoundException.class);
    }

    private void seed(String email, String rawPassword) {
        TenantContext.runAsSystem(
                "test: seed super admin",
                () -> superAdmins.save(new SuperAdmin(email, passwordEncoder.encode(rawPassword))));
    }

    private static String uniqueEmail() {
        return "sa-" + UUID.randomUUID().toString().substring(0, 8) + "@caderly.test";
    }
}
