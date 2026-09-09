package com.caderly.caderlyhr.e2e;

import static org.assertj.core.api.Assertions.assertThat;

import com.caderly.caderlyhr.identity.AppUser;
import com.caderly.caderlyhr.identity.AppUserRepository;
import com.caderly.caderlyhr.identity.Role;
import com.caderly.caderlyhr.support.PlaywrightE2ETestBase;
import com.caderly.caderlyhr.tenant.Tenant;
import com.caderly.caderlyhr.tenant.TenantContext;
import com.caderly.caderlyhr.tenant.TenantRepository;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * The Phase 1.4 DoD's headline flow, end to end through a real browser (CLAUDE.md §3, §8):
 * Admin creates an Employee, the invite is accepted, the Employee edits their own profile, Admin
 * terminates them, and login is blocked afterward. First Playwright usage in this codebase —
 * deferred twice already (1.2, 1.3); CURRENT_PHASE.md names 1.4 as the phase with enough screens
 * to justify standing up the harness.
 *
 * <p>Runs against a real embedded server ({@code RANDOM_PORT}) so htmx/Alpine/Bootstrap JS
 * actually executes, unlike {@code MockMvc}. The invite link is read directly from {@code
 * email_outbox} (system-scoped, no RLS) rather than a real mailbox — the same shortcut {@code
 * InviteAndResetServiceTest} already takes; wiring a browser to an SMTP-sink inbox is out of
 * scope for what this test is proving.
 */
class EmployeeLifecycleE2ETest extends PlaywrightE2ETestBase {

    @Autowired private TenantRepository tenants;
    @Autowired private AppUserRepository appUsers;
    @Autowired private PasswordEncoder passwordEncoder;

    private String adminEmail;

    @Test
    void createInviteAcceptEditTerminate() {
        String slug = "e2e" + UUID.randomUUID().toString().substring(0, 8);
        baseUrl = "http://" + slug + ".localhost:" + port;
        adminEmail = "admin-" + UUID.randomUUID() + "@example.test";
        String employeeEmail = "employee-" + UUID.randomUUID() + "@example.test";
        seedTenantAndAdmin(slug, adminEmail);

        loginAs(adminEmail, "AdminPassphrase1");
        assertThat(page.url()).isEqualTo(baseUrl + "/");

        createEmployee(employeeEmail);
        String rawToken = tokenFromLastEmailTo(employeeEmail);

        acceptInvite(rawToken);
        assertThat(page.url()).contains("/login").contains("inviteAccepted");

        loginAs(employeeEmail, EMPLOYEE_PASSWORD);
        assertThat(page.url()).isEqualTo(baseUrl + "/");

        page.navigate(baseUrl + "/profile");
        assertThat(page.url()).contains("/profile/");
        page.fill("#profile-phone", "+15551234567");
        page.locator("#tab-content form button:has-text('Save')").first().click();
        page.waitForSelector(".toast-body:has-text('Profile updated')");
        assertThat(page.inputValue("#profile-phone")).isEqualTo("+15551234567");

        logout();

        loginAs(adminEmail, "AdminPassphrase1");
        terminateEmployee();

        page.navigate(baseUrl + "/login");
        page.fill("#email", employeeEmail);
        page.fill("#password", EMPLOYEE_PASSWORD);
        page.click("button[type=submit]");
        assertThat(page.url()).contains("error");
    }

    private void seedTenantAndAdmin(String slug, String adminEmail) {
        // Two separate calls, not nested: TenantIdentifierResolver checks isSystem() first, so a
        // TenantContext.set(...) called from inside a runAsSystem(...) lambda is ignored and the
        // entity gets a sentinel tenant id instead (same reason TenantIsolationTestBase's
        // seedTenant/asTenant helpers are two distinct methods, never composed).
        UUID tenantId =
                TenantContext.runAsSystem(
                        "e2e test: seed tenant", () -> tenants.save(new Tenant(slug, "E2E Co")).getId());

        TenantContext.set(tenantId);
        try {
            AppUser admin = AppUser.active(adminEmail, passwordEncoder.encode("AdminPassphrase1"));
            admin.grant(Role.ADMIN);
            appUsers.save(admin);
        } finally {
            TenantContext.clear();
        }
    }

    private void createEmployee(String email) {
        page.navigate(baseUrl + "/admin/employees");
        page.click("button:has-text('Add Employee')");
        page.waitForSelector("#create-firstName");
        page.fill("#create-firstName", "Jane");
        page.fill("#create-lastName", "Doe");
        page.fill("#create-email", email);
        page.click("#employeeOffcanvasBody button:has-text('Create')");
        page.waitForSelector(".toast-body:has-text('Employee created')");
    }

    private void terminateEmployee() {
        page.navigate(baseUrl + "/admin/employees");
        page.click("button[aria-label='Terminate employee']");
        page.waitForSelector(".modal.show");
        page.fill(".modal.show input[type=date]", LocalDate.now().toString());
        page.click(".modal.show button:has-text('Terminate')");
        page.waitForSelector(".toast-body:has-text('termination scheduled')");
    }
}
