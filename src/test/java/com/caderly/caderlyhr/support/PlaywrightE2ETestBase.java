package com.caderly.caderlyhr.support;

import static org.assertj.core.api.Assertions.assertThat;

import com.caderly.caderlyhr.TestcontainersConfiguration;
import com.caderly.caderlyhr.notifications.system.EmailOutbox;
import com.caderly.caderlyhr.notifications.system.EmailOutboxRepository;
import com.caderly.caderlyhr.tenant.TenantContext;
import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

/**
 * Shared Playwright lifecycle and login/invite-accept helpers for {@code e2e/*E2ETest} classes.
 * Tenant/admin seeding is deliberately NOT hoisted here — the four E2E tests seed their fixture
 * tenant three genuinely different ways (plain admin, {@code Employee}-linked admin, dual-user
 * seeding), so each subclass keeps its own {@code seedTenantAndAdmin}/{@code seedTenantAndUsers}.
 */
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
public abstract class PlaywrightE2ETestBase {

    protected static final Pattern TOKEN_IN_LINK = Pattern.compile("[?&]token=([A-Za-z0-9_%\\-]+)");
    protected static final String EMPLOYEE_PASSWORD = "NewPassphrase1";

    @LocalServerPort protected int port;

    @Autowired protected EmailOutboxRepository outbox;

    private static Playwright playwright;
    private static Browser browser;
    private BrowserContext context;
    protected Page page;

    protected String baseUrl;

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
        context = browser.newContext(contextOptions());
        page = context.newPage();
        // Playwright's 30s default is tight when the full suite runs every Testcontainers DB and
        // Chromium concurrently; doubling it avoids flaky timeouts under that load without masking
        // a genuinely broken selector (a broken one still fails, just slower).
        page.setDefaultTimeout(60_000);
    }

    @AfterEach
    void closeContext() {
        context.close();
    }

    /** Override to customize the browser context (e.g. enabling downloads). */
    protected Browser.NewContextOptions contextOptions() {
        return new Browser.NewContextOptions();
    }

    protected void loginAs(String email, String password) {
        page.navigate(baseUrl + "/login");
        page.fill("#email", email);
        page.fill("#password", password);
        page.click("button[type=submit]");
    }

    protected void logout() {
        page.click("#accountMenuButton");
        page.click("button:has-text('Log out')");
    }

    protected void acceptInvite(String rawToken) {
        page.navigate(baseUrl + "/accept-invite?token=" + rawToken);
        page.fill("#password", EMPLOYEE_PASSWORD);
        page.fill("#confirmPassword", EMPLOYEE_PASSWORD);
        page.click("button[type=submit]");
    }

    /** {@code email_outbox} is system-scoped (no RLS), same as {@code InviteAndResetServiceTest}. */
    protected String tokenFromLastEmailTo(String email) {
        List<EmailOutbox> found =
                TenantContext.runAsSystem(
                        "e2e test: read outbox",
                        () -> outbox.findAll().stream().filter(row -> row.toEmail().equals(email)).toList());
        assertThat(found).as("queued invite email to %s", email).isNotEmpty();
        Matcher matcher = TOKEN_IN_LINK.matcher(found.getLast().bodyHtml());
        assertThat(matcher.find()).as("token in email body").isTrue();
        return URLDecoder.decode(matcher.group(1), StandardCharsets.UTF_8);
    }
}
