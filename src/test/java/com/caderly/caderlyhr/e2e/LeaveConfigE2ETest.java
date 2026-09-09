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
 * Phase 1.5's DoD headline flow, end to end through a real browser (CLAUDE.md §3, §8): Admin
 * defines a leave type, adds a holiday, creates a hired employee — the employee's balance appears
 * on their Home dashboard. CSV bulk upload itself is covered by {@code
 * PublicHolidayServiceTest}'s integration tests, not repeated here; this spec's job is proving
 * the browser-rendered path, one manually-added holiday is enough for that.
 *
 * <p>Mirrors {@code EmployeeLifecycleE2ETest}'s helpers (login, invite-token-from-outbox) rather
 * than re-deriving them, per CURRENT_PHASE.md.
 */
class LeaveConfigE2ETest extends PlaywrightE2ETestBase {

    @Autowired private TenantRepository tenants;
    @Autowired private AppUserRepository appUsers;
    @Autowired private PasswordEncoder passwordEncoder;

    @Test
    void adminDefinesLeaveTypeAndHoliday_employeeSeesBalanceOnHome() {
        String slug = "leave-e2e" + UUID.randomUUID().toString().substring(0, 8);
        baseUrl = "http://" + slug + ".localhost:" + port;
        String adminEmail = "admin-" + UUID.randomUUID() + "@example.test";
        String employeeEmail = "employee-" + UUID.randomUUID() + "@example.test";
        seedTenantAndAdmin(slug, adminEmail);

        loginAs(adminEmail, "AdminPassphrase1");
        assertThat(page.url()).isEqualTo(baseUrl + "/");

        createLeaveType();
        createHoliday();
        createEmployeeWithHireDate(employeeEmail);

        String rawToken = tokenFromLastEmailTo(employeeEmail);
        acceptInvite(rawToken);
        loginAs(employeeEmail, EMPLOYEE_PASSWORD);

        assertThat(page.url()).isEqualTo(baseUrl + "/");
        page.waitForSelector(".card:has-text('Annual')");
        assertThat(page.content()).contains("Book Time Off");
    }

    private void seedTenantAndAdmin(String slug, String adminEmail) {
        // Two separate calls, not nested — see EmployeeLifecycleE2ETest's identical comment.
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

    private void createLeaveType() {
        page.navigate(baseUrl + "/admin/leave-types");
        page.click("button:has-text('Add Leave Type')");
        page.waitForSelector("#leave-type-name");
        page.fill("#leave-type-name", "Annual");
        page.fill("#leave-type-defaultAnnualAllowance", "24");
        page.click("#leaveTypeOffcanvasBody button:has-text('Save leave type')");
        page.waitForSelector(".toast-body:has-text('Leave type created')");
    }

    private void createHoliday() {
        page.navigate(baseUrl + "/admin/holidays");
        page.click("button:has-text('Add Holiday')");
        page.waitForSelector("#holiday-date");
        page.fill("#holiday-date", LocalDate.now().plusMonths(1).toString());
        page.fill("#holiday-name", "Founders Day");
        page.click("#holidayOffcanvasBody button:has-text('Save holiday')");
        page.waitForSelector(".toast-body:has-text('Holiday added')");
    }

    private void createEmployeeWithHireDate(String email) {
        page.navigate(baseUrl + "/admin/employees");
        page.click("button:has-text('Add Employee')");
        page.waitForSelector("#create-firstName");
        page.fill("#create-firstName", "Priya");
        page.fill("#create-lastName", "Shah");
        page.fill("#create-email", email);
        page.fill("#create-hireDate", LocalDate.now().toString());
        page.click("#employeeOffcanvasBody button:has-text('Create')");
        page.waitForSelector(".toast-body:has-text('Employee created')");
    }

}
