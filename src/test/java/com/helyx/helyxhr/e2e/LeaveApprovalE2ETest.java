package com.helyx.helyxhr.e2e;

import static org.assertj.core.api.Assertions.assertThat;

import com.helyx.helyxhr.identity.AppUser;
import com.helyx.helyxhr.identity.AppUserRepository;
import com.helyx.helyxhr.identity.Role;
import com.helyx.helyxhr.notifications.system.EmailOutbox;
import com.helyx.helyxhr.notifications.system.EmailOutboxRepository;
import com.helyx.helyxhr.people.Employee;
import com.helyx.helyxhr.people.EmployeeForms;
import com.helyx.helyxhr.people.EmployeeRepository;
import com.helyx.helyxhr.people.EmployeeService;
import com.helyx.helyxhr.tenant.Tenant;
import com.helyx.helyxhr.tenant.TenantContext;
import com.helyx.helyxhr.tenant.TenantRepository;
import com.helyx.helyxhr.timeoff.LeaveBalance;
import com.helyx.helyxhr.timeoff.LeaveBalanceRepository;
import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.temporal.TemporalAdjusters;
import java.util.List;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;

/**
 * Phase 1.6's DoD headline flow, end to end through a real browser (CLAUDE.md §3, §8): an Employee
 * books time off via the Home dashboard's modal, the request has no manager to route to so it
 * falls back to the tenant's Admin(s) (PRD §12.4 step 2), the Admin approves it from the For
 * Action inbox, and the employee's balance reflects the debit. Mirrors {@code
 * LeaveConfigE2ETest}/{@code EmployeeLifecycleE2ETest}'s helpers rather than re-deriving them.
 */
@Import(com.helyx.helyxhr.TestcontainersConfiguration.class)
@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class LeaveApprovalE2ETest {

    private static final Pattern TOKEN_IN_LINK = Pattern.compile("[?&]token=([A-Za-z0-9_%\\-]+)");
    private static final String EMPLOYEE_PASSWORD = "NewPassphrase1";
    private static final String ADMIN_PASSWORD = "AdminPassphrase1";

    @LocalServerPort private int port;

    @Autowired private TenantRepository tenants;
    @Autowired private AppUserRepository appUsers;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private EmailOutboxRepository outbox;
    @Autowired private EmployeeRepository employees;
    @Autowired private EmployeeService employeeService;
    @Autowired private LeaveBalanceRepository balances;

    private static Playwright playwright;
    private static Browser browser;
    private BrowserContext context;
    private Page page;

    private String baseUrl;
    private UUID tenantId;

    @BeforeAll
    static void launchBrowser() {
        playwright = Playwright.create();
        browser = playwright.chromium().launch(new BrowserType.LaunchOptions().setHeadless(true));
    }

    @AfterAll
    static void closeBrowser() {
        browser.close();
        playwright.close();
    }

    @BeforeEach
    void newPage() {
        context = browser.newContext();
        page = context.newPage();
    }

    @AfterEach
    void closeContext() {
        context.close();
    }

    @Test
    void employeeBooksTimeOff_adminApproves_balanceUpdatesAndEmailQueued() {
        String slug = "leave-appr-e2e" + UUID.randomUUID().toString().substring(0, 8);
        baseUrl = "http://" + slug + ".localhost:" + port;
        String adminEmail = "admin-" + UUID.randomUUID() + "@example.test";
        String employeeEmail = "employee-" + UUID.randomUUID() + "@example.test";
        seedTenantAndAdmin(slug, adminEmail);

        loginAs(adminEmail, ADMIN_PASSWORD);
        createLeaveType();
        createEmployeeWithHireDate(employeeEmail);

        String rawToken = tokenFromLastEmailTo(employeeEmail);
        acceptInvite(rawToken);
        loginAs(employeeEmail, EMPLOYEE_PASSWORD);

        LocalDate start = LocalDate.now().with(TemporalAdjusters.next(DayOfWeek.MONDAY));
        LocalDate end = start.plusDays(1); // Monday-Tuesday: two working days, no weekend in range.
        bookTimeOff(start, end);
        page.waitForSelector(".toast-body:has-text('Time off request submitted')");

        // "requested" email queued to the Admin fallback (no manager set on this employee).
        assertThat(tokenFromLastEmailTo(adminEmail, "requested time off")).isNotEmpty();

        logout();
        loginAs(adminEmail, ADMIN_PASSWORD);
        page.navigate(baseUrl + "/for-action");
        page.waitForSelector("button:has-text('Approve')");
        page.click("button:has-text('Approve')");
        page.waitForSelector(".toast-body:has-text('Request approved')");

        // Approve only swaps #pendingList (UI Guidelines §8.6: "row removed via htmx") — the
        // Completed tab's content is populated once, on page load, so a fresh navigation is what
        // picks up the just-decided row.
        page.navigate(baseUrl + "/for-action");
        page.click("#completed-tab");
        page.waitForSelector(".badge:has-text('Approved')");

        TenantContext.set(tenantId);
        UUID employeeId;
        List<LeaveBalance> balance;
        try {
            employeeId =
                    employees.findAllByStatusNot(com.helyx.helyxhr.people.EmployeeStatus.TERMINATED).stream()
                            .filter(e -> e.email().equals(employeeEmail))
                            .findFirst()
                            .orElseThrow()
                            .requireId();
            balance = balances.findAllByEmployeeIdAndYear(employeeId, start.getYear());
        } finally {
            TenantContext.clear();
        }
        assertThat(balance).hasSize(1);
        assertThat(balance.getFirst().used()).isEqualByComparingTo("2.00");
    }

    /**
     * The Admin needs a real linked {@link Employee}, not just a login: {@code PeopleFacade}'s
     * Admin-fallback lookup (routing a no-manager request) and {@code LeaveApprovalController}'s
     * For Action page both resolve the acting Admin's employee record, and silently skip/404 an
     * Admin login with none (see ADR 0010's "Admin login with no linked Employee" note) — a plain
     * {@code AppUser.active(...)} login-only account, as {@code LeaveConfigE2ETest}'s Admin uses,
     * is exactly that case and is not enough here.
     */
    private void seedTenantAndAdmin(String slug, String adminEmail) {
        tenantId =
                TenantContext.runAsSystem(
                        "e2e test: seed tenant", () -> tenants.save(new Tenant(slug, "E2E Co")).getId());

        TenantContext.set(tenantId);
        try {
            Employee admin =
                    employeeService.create(
                            new EmployeeForms.CreateEmployee(
                                    "Ann",
                                    "Admin",
                                    adminEmail,
                                    null, null, null, null, null, null, null,
                                    LocalDate.now(),
                                    null, null, null, null, null, null, null, null),
                            baseUrl,
                            "E2E Co");
            AppUser user = appUsers.findById(admin.userId()).orElseThrow();
            user.grant(Role.ADMIN);
            user.acceptInvite(passwordEncoder.encode(ADMIN_PASSWORD)); // real password, real /login
            appUsers.save(user);
        } finally {
            TenantContext.clear();
        }
    }

    private void loginAs(String email, String password) {
        page.navigate(baseUrl + "/login");
        page.fill("#email", email);
        page.fill("#password", password);
        page.click("button[type=submit]");
    }

    private void logout() {
        page.click("#accountMenuButton");
        page.click("button:has-text('Log out')");
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

    private void acceptInvite(String rawToken) {
        page.navigate(baseUrl + "/accept-invite?token=" + rawToken);
        page.fill("#password", EMPLOYEE_PASSWORD);
        page.fill("#confirmPassword", EMPLOYEE_PASSWORD);
        page.click("button[type=submit]");
    }

    private void bookTimeOff(LocalDate start, LocalDate end) {
        page.navigate(baseUrl + "/");
        page.click("button:has-text('Book time off')");
        page.waitForSelector("#leave-start-date");
        page.click("label:has-text('Annual')");
        page.fill("#leave-start-date", start.toString());
        page.fill("#leave-end-date", end.toString());
        page.click("button:has-text('Submit request')");
    }

    /** {@code email_outbox} is system-scoped (no RLS), same as {@code EmployeeLifecycleE2ETest}. */
    private String tokenFromLastEmailTo(String email) {
        List<EmailOutbox> found =
                TenantContext.runAsSystem(
                        "e2e test: read outbox",
                        () -> outbox.findAll().stream().filter(row -> row.toEmail().equals(email)).toList());
        assertThat(found).as("queued invite email to %s", email).isNotEmpty();
        Matcher matcher = TOKEN_IN_LINK.matcher(found.getLast().bodyHtml());
        assertThat(matcher.find()).as("token in email body").isTrue();
        return URLDecoder.decode(matcher.group(1), StandardCharsets.UTF_8);
    }

    private List<EmailOutbox> tokenFromLastEmailTo(String email, String subjectContains) {
        return TenantContext.runAsSystem(
                "e2e test: read outbox",
                () ->
                        outbox.findAll().stream()
                                .filter(row -> row.toEmail().equals(email) && row.subject().contains(subjectContains))
                                .toList());
    }
}
