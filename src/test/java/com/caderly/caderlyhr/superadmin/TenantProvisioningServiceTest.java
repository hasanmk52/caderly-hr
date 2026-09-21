package com.caderly.caderlyhr.superadmin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.caderly.caderlyhr.TestcontainersConfiguration;
import com.caderly.caderlyhr.common.ConflictException;
import com.caderly.caderlyhr.identity.AppUser;
import com.caderly.caderlyhr.identity.AppUserRepository;
import com.caderly.caderlyhr.identity.Role;
import com.caderly.caderlyhr.identity.UserStatus;
import com.caderly.caderlyhr.notifications.system.EmailOutbox;
import com.caderly.caderlyhr.notifications.system.EmailOutboxRepository;
import com.caderly.caderlyhr.people.Employee;
import com.caderly.caderlyhr.people.EmployeeRepository;
import com.caderly.caderlyhr.tenant.Tenant;
import com.caderly.caderlyhr.tenant.TenantContext;
import com.caderly.caderlyhr.tenant.TenantRepository;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

/**
 * {@link TenantProvisioningService}'s "create tenant + first Admin" flow (PRD FR-1.8, Phase
 * 1.13). Every test here runs from a thread with no {@code TenantContext} set — exactly the
 * Super Admin realm's situation (Global Constraint 1), matching {@code TenantServiceTest}'s
 * pattern exactly — so "does not throw {@code IllegalStateException}" is the load-bearing
 * assertion throughout, not just the more obvious value assertions.
 */
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("test")
@SpringBootTest
class TenantProvisioningServiceTest {

    private static final String BASE_URL = "https://acme.localhost";

    @Autowired private TenantProvisioningService provisioning;
    @Autowired private TenantRepository tenantRepository;
    @Autowired private AppUserRepository users;
    @Autowired private EmailOutboxRepository outbox;
    @Autowired private EmployeeRepository employees;

    @AfterEach
    void clearTenantContext() {
        TenantContext.clear();
    }

    @Test
    void provision_fromNoTenantContext_doesNotThrowAndPersistsTenantFields() {
        String slug = uniqueSlug();
        String adminEmail = uniqueEmail();

        // The single most important assertion in this test: TenantContext is empty on this
        // thread (no @BeforeEach sets it), matching the Super Admin realm exactly. If the
        // createTenant-then-set-TenantContext-then-invite sequencing were wrong, this line
        // throws IllegalStateException from TenantSessionVariableListener, not the exception a
        // wrong slug or duplicate email would produce.
        UUID tenantId =
                provisioning.provision(
                        slug, "Acme Inc", "Asia/Kolkata", 32, "https://logo.example/a.png", adminEmail, BASE_URL);

        assertThat(tenantId).isNotNull();
        Tenant saved = findTenant(tenantId);
        assertThat(saved.getSlug()).isEqualTo(slug);
        assertThat(saved.getName()).isEqualTo("Acme Inc");
        assertThat(saved.getTimezone()).isEqualTo("Asia/Kolkata");
        assertThat(saved.getWeekendDays()).isEqualTo(32);
        assertThat(saved.getLogoUrl()).isEqualTo("https://logo.example/a.png");
    }

    @Test
    void provision_queuesExactlyOneInviteEmailToTheFirstAdmin() {
        String adminEmail = uniqueEmail();

        provisioning.provision(uniqueSlug(), "Acme Inc", "UTC", 96, null, adminEmail, BASE_URL);

        List<EmailOutbox> queued =
                TenantContext.runAsSystem(
                        "test: read outbox",
                        () -> outbox.findAll().stream().filter(row -> row.toEmail().equals(adminEmail)).toList());
        assertThat(queued).hasSize(1);
        assertThat(queued.getFirst().eventType()).isEqualTo("INVITE");
        assertThat(queued.getFirst().toEmail()).isEqualTo(adminEmail);
    }

    @Test
    void provision_createsInvitedAdminWithExactlyTheAdminRoleAndNoEmployeeRow() {
        String adminEmail = uniqueEmail();

        UUID tenantId =
                provisioning.provision(uniqueSlug(), "Acme Inc", "UTC", 96, null, adminEmail, BASE_URL);

        TenantContext.set(tenantId);
        try {
            AppUser created = users.findByEmail(adminEmail).orElseThrow();
            assertThat(created.status()).isEqualTo(UserStatus.INVITED);
            assertThat(created.roles()).containsExactly(Role.ADMIN);

            // No Employee row is created for a Super-Admin-provisioned first Admin: Employee.userId
            // is a nullable plain column with no reverse FK, so an AppUser stands alone here.
            List<Employee> tenantEmployees = employees.findAll();
            assertThat(tenantEmployees).isEmpty();
        } finally {
            TenantContext.clear();
        }
    }

    @Test
    void provision_calledTwiceWithTheSameSlug_throwsConflict() {
        String slug = uniqueSlug();
        provisioning.provision(slug, "First", "UTC", 96, null, uniqueEmail(), BASE_URL);

        assertThatThrownBy(
                        () -> provisioning.provision(slug, "Second", "UTC", 96, null, uniqueEmail(), BASE_URL))
                .isInstanceOf(ConflictException.class);
    }

    private static String uniqueSlug() {
        return "prov-test-" + UUID.randomUUID().toString().substring(0, 8);
    }

    private static String uniqueEmail() {
        return "admin-" + UUID.randomUUID() + "@example.test";
    }

    /**
     * Direct assertions against the repository, bypassing the facade, still open a Hibernate
     * session — which resolves a multi-tenant identifier for ANY entity, {@code tenant} included
     * (Global Constraint 1) — so this needs the same {@code runAsSystem} wrapping production code
     * requires, exactly like {@code TenantServiceTest#findTenant}.
     */
    private Tenant findTenant(UUID tenantId) {
        return TenantContext.runAsSystem(
                "test: read tenant row directly", () -> tenantRepository.findById(tenantId).orElseThrow());
    }
}
