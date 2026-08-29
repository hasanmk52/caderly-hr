package com.caderly.caderlyhr.e2e;

import static org.assertj.core.api.Assertions.assertThat;

import com.caderly.caderlyhr.TestcontainersConfiguration;
import com.caderly.caderlyhr.identity.AppUser;
import com.caderly.caderlyhr.identity.AppUserRepository;
import com.caderly.caderlyhr.identity.Role;
import com.caderly.caderlyhr.tenant.Tenant;
import com.caderly.caderlyhr.tenant.TenantContext;
import com.caderly.caderlyhr.tenant.TenantRepository;
import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Download;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;

/**
 * Phase 1.7's DoD headline flow (PRD §6.7 FR-7.1, §9.5 US-F.1), end to end through a real browser:
 * Admin uploads the company handbook to Files, and any signed-in Employee downloads it. Neither
 * side needs a linked {@code people.Employee} record — Files authorization is role/session-based
 * only (CLAUDE.md §6 A01) — so both principals here are seeded as plain {@code AppUser}s, the same
 * shortcut {@code EmployeeLifecycleE2ETest} takes for its Admin.
 */
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class FilesE2ETest {

    @LocalServerPort private int port;

    @Autowired private TenantRepository tenants;
    @Autowired private AppUserRepository appUsers;
    @Autowired private PasswordEncoder passwordEncoder;

    @TempDir private Path tempDir;

    private static Playwright playwright;
    private static Browser browser;
    private BrowserContext context;
    private Page page;

    private String baseUrl;

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
        context = browser.newContext(new Browser.NewContextOptions().setAcceptDownloads(true));
        page = context.newPage();
    }

    @AfterEach
    void closeContext() {
        context.close();
    }

    @Test
    void adminUploadsHandbook_employeeDownloadsIt() throws Exception {
        String slug = "files-e2e" + UUID.randomUUID().toString().substring(0, 8);
        baseUrl = "http://" + slug + ".localhost:" + port;
        String adminEmail = "admin-" + UUID.randomUUID() + "@example.test";
        String employeeEmail = "employee-" + UUID.randomUUID() + "@example.test";
        seedTenantAndUsers(slug, adminEmail, employeeEmail);

        Path handbook = tempDir.resolve("Employee Handbook.pdf");
        Files.writeString(handbook, "%PDF-1.4\nHandbook contents\n%%EOF", StandardCharsets.UTF_8);

        loginAs(adminEmail, "AdminPassphrase1");
        page.navigate(baseUrl + "/files");
        page.setInputFiles("input[type=file]", handbook);
        page.click("button:has-text('Upload file')");
        page.waitForSelector(".toast-body:has-text('File uploaded')");
        page.waitForSelector("td:has-text('Employee Handbook.pdf')");
        logout();

        loginAs(employeeEmail, "EmployeePassphrase1");
        page.navigate(baseUrl + "/files");
        page.waitForSelector("td:has-text('Employee Handbook.pdf')");
        Download download = page.waitForDownload(() -> page.click("a[aria-label='Download file']"));
        Path downloaded = tempDir.resolve("downloaded.pdf");
        download.saveAs(downloaded);
        assertThat(Files.readString(downloaded, StandardCharsets.UTF_8)).contains("Handbook contents");
    }

    private void seedTenantAndUsers(String slug, String adminEmail, String employeeEmail) {
        UUID tenantId =
                TenantContext.runAsSystem(
                        "e2e test: seed tenant", () -> tenants.save(new Tenant(slug, "Files E2E Co")).getId());

        TenantContext.set(tenantId);
        try {
            AppUser admin = AppUser.active(adminEmail, passwordEncoder.encode("AdminPassphrase1"));
            admin.grant(Role.ADMIN);
            appUsers.save(admin);

            AppUser employee = AppUser.active(employeeEmail, passwordEncoder.encode("EmployeePassphrase1"));
            employee.grant(Role.EMPLOYEE);
            appUsers.save(employee);
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
}
