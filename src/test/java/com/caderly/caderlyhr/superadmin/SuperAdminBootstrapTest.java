package com.caderly.caderlyhr.superadmin;

import static org.assertj.core.api.Assertions.assertThat;

import com.caderly.caderlyhr.TestcontainersConfiguration;
import com.caderly.caderlyhr.tenant.TenantContext;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;

/**
 * Constructs {@link SuperAdminBootstrap} directly with test-chosen bootstrap-email/password
 * values rather than autowiring the application's own {@code SuperAdminBootstrap} bean: the
 * "test" profile leaves {@code caderly.superadmin.bootstrap-email/password} unset (see
 * application-test.yml), so the app-managed instance already ran as a no-op during context
 * startup and never touches the table these tests assert against.
 *
 * <p>{@link #clearSuperAdmins()} empties the table before every test so "the table is empty"
 * holds regardless of what other test classes sharing this Testcontainers Postgres instance have
 * written to it.
 */
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("test")
@SpringBootTest
class SuperAdminBootstrapTest {

    private static final String PASSWORD = "Bootstrap12345!";

    @Autowired private SuperAdminRepository superAdmins;
    @Autowired private PasswordEncoder passwordEncoder;

    @BeforeEach
    void clearSuperAdmins() {
        TenantContext.runAsSystem(
                "test: clear super_admin before test",
                () -> {
                    superAdmins.deleteAll();
                    return null;
                });
    }

    @AfterEach
    void clearTenantContext() {
        TenantContext.clear();
    }

    @Test
    void run_withBothEnvVarsSetAndTableEmpty_createsExactlyOneRow() {
        String email = uniqueEmail();
        SuperAdminBootstrap bootstrap =
                new SuperAdminBootstrap(superAdmins, passwordEncoder, email, PASSWORD);

        bootstrap.run(null);

        assertThat(countRows()).isEqualTo(1);
        assertThat(findByEmail(email)).isPresent();
    }

    @Test
    void run_calledTwice_secondCallIsANoOpAndRowCountIsUnchanged() {
        String email = uniqueEmail();
        SuperAdminBootstrap bootstrap =
                new SuperAdminBootstrap(superAdmins, passwordEncoder, email, PASSWORD);

        bootstrap.run(null);
        long countAfterFirstRun = countRows();
        UUID idAfterFirstRun = findByEmail(email).orElseThrow().requireId();

        bootstrap.run(null);

        // Not just "no exception" — the row count is asserted unchanged and the surviving row is
        // proven to be the SAME row (same id), not a duplicate-then-cleaned-up coincidence.
        assertThat(countRows()).isEqualTo(countAfterFirstRun);
        assertThat(findByEmail(email).orElseThrow().requireId()).isEqualTo(idAfterFirstRun);
    }

    @Test
    void run_withBlankBootstrapEmail_doesNotCreateARow() {
        SuperAdminBootstrap bootstrap = new SuperAdminBootstrap(superAdmins, passwordEncoder, "", PASSWORD);

        bootstrap.run(null);

        assertThat(countRows()).isZero();
    }

    @Test
    void run_withBlankBootstrapPassword_doesNotCreateARow() {
        SuperAdminBootstrap bootstrap =
                new SuperAdminBootstrap(superAdmins, passwordEncoder, uniqueEmail(), "");

        bootstrap.run(null);

        assertThat(countRows()).isZero();
    }

    private long countRows() {
        return TenantContext.runAsSystem("test: count super_admin rows", superAdmins::count);
    }

    private Optional<SuperAdmin> findByEmail(String email) {
        return TenantContext.runAsSystem(
                "test: find super admin by email", () -> superAdmins.findByEmail(email));
    }

    private static String uniqueEmail() {
        return "sa-" + UUID.randomUUID().toString().substring(0, 8) + "@caderly.test";
    }
}
