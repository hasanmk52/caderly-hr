package com.caderly.caderlyhr.superadmin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.caderly.caderlyhr.TestcontainersConfiguration;
import com.caderly.caderlyhr.audit.LoginAuditRepository;
import com.caderly.caderlyhr.audit.system.LoginAudit;
import com.caderly.caderlyhr.audit.system.LoginAudit.FailureReason;
import com.caderly.caderlyhr.identity.AppUser;
import com.caderly.caderlyhr.identity.AppUserRepository;
import com.caderly.caderlyhr.identity.Role;
import com.caderly.caderlyhr.security.RateLimitFilter;
import com.caderly.caderlyhr.tenant.Tenant;
import com.caderly.caderlyhr.tenant.TenantContext;
import com.caderly.caderlyhr.tenant.TenantRepository;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The Super Admin realm is a second, parallel security context: its own filter chain, its own
 * {@code AuthenticationManager}, and no tenant resolution at all. The cases below are the ones
 * that would silently stop being true if the two chains ever shared an {@code
 * AuthenticationManager} or a {@code UserDetailsService} — a session from one realm reaching the
 * other's pages, or one realm's credentials being checked against the other's user store.
 *
 * <p>The test profile allowlists {@code 127.0.0.1/32} (see {@code application-test.yml}), which is
 * MockMvc's default remote address — so a case that wants to look like a non-allowlisted client
 * says so explicitly with an {@code X-Forwarded-For} header, exactly as {@code
 * AuthenticationFlowTest} does for the per-IP lockout.
 */
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("test")
@SpringBootTest
@AutoConfigureMockMvc
class SuperAdminSecurityConfigTest {

    private static final String PASSWORD = "C0rrectHorseBattery";
    private static final String OUTSIDE_IP = "198.51.100.7";
    private static final String SUPER_ADMIN_SECURITY_CONTEXT_KEY =
            SuperAdminSecurityConfig.SECURITY_CONTEXT_KEY;

    @Autowired private MockMvc mockMvc;
    @Autowired private SuperAdminRepository superAdmins;
    @Autowired private TenantRepository tenants;
    @Autowired private AppUserRepository users;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private TransactionTemplate transactions;
    @Autowired private RateLimitFilter rateLimitFilter;
    @Autowired private LoginAuditRepository loginAudits;

    private String superAdminEmail;

    @BeforeEach
    void seed() {
        // Same reason as AuthenticationFlowTest: several cases here post to /superadmin/login from
        // one address, and the limit is 10/min/IP.
        rateLimitFilter.clearBuckets(null);

        superAdminEmail = "root-" + shortId() + "@caderly.test";
        asSystem(
                () ->
                        transactions.execute(
                                status ->
                                        superAdmins.save(
                                                new SuperAdmin(superAdminEmail, passwordEncoder.encode(PASSWORD)))));
    }

    @Test
    void superAdminPage_whenUnauthenticated_redirectsToTheSuperAdminLoginNotTheTenantLogin()
            throws Exception {
        mockMvc
                .perform(get(URI.create("http://localhost/superadmin/tenants")))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/superadmin/login"));
    }

    @Test
    void superAdminLogin_withValidCredentials_authenticatesAgainstTheSuperAdminStore()
            throws Exception {
        mockMvc
                .perform(superAdminLoginRequest(superAdminEmail, PASSWORD))
                .andExpect(redirectedUrl("/superadmin/tenants"));
    }

    @Test
    void superAdminLogin_withATenantUsersCredentials_isRejected() throws Exception {
        // The realms must not cross-authenticate: a real tenant account, correct password, is not
        // a Super Admin and must not become one just because both chains live in one application.
        String slug = "acme" + shortId();
        UUID tenantId = seedTenant(slug);
        String email = "admin-" + shortId() + "@example.test";
        seedAdmin(tenantId, email);

        mockMvc
                .perform(superAdminLoginRequest(email, PASSWORD))
                .andExpect(redirectedUrl("/superadmin/login?error"));
    }

    @Test
    void tenantLogin_withASuperAdminsCredentials_isRejected() throws Exception {
        // The other direction of the same rule: a Super Admin is not a user of any tenant, and
        // the tenant chain must not fall back to super_admin when app_user has no such address.
        String slug = "acme" + shortId();
        seedTenant(slug);

        mockMvc
                .perform(
                        post(URI.create("http://" + slug + ".localhost/login"))
                                .param("email", superAdminEmail)
                                .param("password", PASSWORD)
                                .with(csrf()))
                .andExpect(redirectedUrl("/login?error"));
    }

    @Test
    void bothRealms_sendTheSameHardenedResponseHeaders() throws Exception {
        // SecurityHeaders.standard() exists so the two chains cannot drift apart here (PRD §19.6).
        // Compared rather than hard-coded: this asserts the sharing, not the policy text, which
        // stays defined in exactly one place.
        String slug = "acme" + shortId();
        seedTenant(slug);
        var tenantResponse =
                mockMvc.perform(get(URI.create("http://" + slug + ".localhost/login"))).andReturn().getResponse();
        var superAdminResponse =
                mockMvc.perform(get(URI.create("http://localhost/superadmin/tenants"))).andReturn().getResponse();

        // No Strict-Transport-Security here: Spring Security only emits it over HTTPS, and MockMvc
        // requests are plain HTTP, so it is absent on both chains and would prove nothing.
        for (String header :
                List.of(
                        "Content-Security-Policy",
                        "X-Frame-Options",
                        "Referrer-Policy",
                        "Permissions-Policy")) {
            assertThat(superAdminResponse.getHeader(header))
                    .as("%s on the Super Admin chain", header)
                    .isNotNull()
                    .isEqualTo(tenantResponse.getHeader(header));
        }
    }

    @Test
    void superAdminPage_withATenantAdminSession_isNotAuthenticated() throws Exception {
        String slug = "acme" + shortId();
        UUID tenantId = seedTenant(slug);
        String email = "admin-" + shortId() + "@example.test";
        seedAdmin(tenantId, email);
        MockHttpSession session = tenantSession(slug, email);

        // Not 403: the tenant session lives under a different session key, so this chain sees an
        // anonymous request and sends it to its own login page. A 403 here would mean the chain
        // *did* read the tenant realm's principal and merely disliked its authorities.
        mockMvc
                .perform(get(URI.create("http://localhost/superadmin/tenants")).session(session))
                .andExpect(redirectedUrl("/superadmin/login"));
    }

    @Test
    void tenantAdminPage_withASuperAdminSession_isNotAuthenticated() throws Exception {
        String slug = "acme" + shortId();
        seedTenant(slug);
        MockHttpSession session = superAdminSession();

        mockMvc
                .perform(get(URI.create("http://" + slug + ".localhost/admin/users")).session(session))
                .andExpect(redirectedUrl("/login"));
    }

    @Test
    void tenantPageGatedOnlyByIsAuthenticated_withASuperAdminSession_isNotServed() throws Exception {
        // The case a role-based assertion cannot catch. /files is @PreAuthorize("isAuthenticated()")
        // with no dependency on the principal type, so if the tenant chain could read the Super
        // Admin realm's SecurityContext out of the shared HttpSession, ROLE_SUPER_ADMIN would
        // satisfy both anyRequest().authenticated() and the method gate — handing an operator
        // unaudited cross-tenant reads outside the impersonation flow entirely.
        String slug = "acme" + shortId();
        seedTenant(slug);
        MockHttpSession session = superAdminSession();

        mockMvc
                .perform(get(URI.create("http://" + slug + ".localhost/files")).session(session))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/login"));
    }

    @Test
    void superAdminLogin_writesItsContextUnderItsOwnSessionKey() throws Exception {
        // The mechanism the two tests above rely on, asserted directly: the realms are separated
        // by *where* the context is stored, so a regression would be a one-line config change.
        MockHttpSession session = superAdminSession();

        assertThat(session.getAttribute(SUPER_ADMIN_SECURITY_CONTEXT_KEY)).isNotNull();
        assertThat(session.getAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY))
                .isNull();
    }

    @Test
    void superAdminLogin_fromANonAllowlistedIp_isForbiddenAndAuthenticatesNobody() throws Exception {
        MockHttpSession session = new MockHttpSession();

        mockMvc
                .perform(superAdminLoginRequest(superAdminEmail, PASSWORD).header("X-Forwarded-For", OUTSIDE_IP)
                        .session(session))
                .andExpect(status().isForbidden());

        // The correct password was supplied, so "no security context in the session" is only true
        // if the request was turned away before the login filter ever ran.
        assertThat(session.getAttribute(SUPER_ADMIN_SECURITY_CONTEXT_KEY)).isNull();
    }

    @Test
    void superAdminPage_fromANonAllowlistedIp_isForbiddenRatherThanRedirectedToLogin()
            throws Exception {
        mockMvc
                .perform(get(URI.create("http://localhost/superadmin/tenants")).header("X-Forwarded-For", OUTSIDE_IP))
                .andExpect(status().isForbidden());
    }

    @Test
    void tenantPage_fromANonAllowlistedIp_isUnaffectedByTheSuperAdminAllowlist() throws Exception {
        String slug = "acme" + shortId();
        seedTenant(slug);

        mockMvc
                .perform(get(URI.create("http://" + slug + ".localhost/")).header("X-Forwarded-For", OUTSIDE_IP))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/login"));
    }

    @Test
    void superAdminLogin_whenSuccessful_writesATenantlessLoginAuditRow() throws Exception {
        // CLAUDE.md §6 A09 has no realm carve-out: the highest-privilege account in the system is
        // the last one whose logins may go unrecorded.
        mockMvc
                .perform(superAdminLoginRequest(superAdminEmail, PASSWORD))
                .andExpect(redirectedUrl("/superadmin/tenants"));

        LoginAudit row = onlyAuditRowFor(superAdminEmail);
        assertThat(row.success()).isTrue();
        assertThat(row.tenantId()).isNull();
        assertThat(row.failureReason()).isNull();
        assertThat(row.userId()).isEqualTo(superAdminId());
        assertThat(row.ip()).isEqualTo("127.0.0.1");
    }

    @Test
    void superAdminLogin_withAWrongPassword_writesAFailedLoginAuditRow() throws Exception {
        mockMvc
                .perform(superAdminLoginRequest(superAdminEmail, "WrongPassword123"))
                .andExpect(redirectedUrl("/superadmin/login?error"));

        LoginAudit row = onlyAuditRowFor(superAdminEmail);
        assertThat(row.success()).isFalse();
        assertThat(row.tenantId()).isNull();
        // The operator exists, so this is a wrong password rather than an unknown address —
        // distinguished for the audit trail only; the response is identical either way.
        assertThat(row.failureReason()).isEqualTo(FailureReason.BAD_CREDENTIALS.name());
        assertThat(row.userId()).isEqualTo(superAdminId());
    }

    @Test
    void superAdminLogin_withAnUnknownEmail_writesAnUnknownEmailAuditRow() throws Exception {
        String unknown = "nobody-" + shortId() + "@caderly.test";

        mockMvc
                .perform(superAdminLoginRequest(unknown, PASSWORD))
                .andExpect(redirectedUrl("/superadmin/login?error"));

        LoginAudit row = onlyAuditRowFor(unknown);
        assertThat(row.success()).isFalse();
        assertThat(row.failureReason()).isEqualTo(FailureReason.UNKNOWN_EMAIL.name());
        assertThat(row.userId()).isNull();
    }

    @Test
    void superAdminLogin_withAPercentEncodedPath_isStillGatedByTheIpAllowlist() throws Exception {
        // The allowlist and the rate limiter compare request.getRequestURI() — which the servlet
        // spec leaves percent-encoded — against a literal "/superadmin" prefix, while Spring
        // Security matches "/superadmin/**" against the *decoded* path. If those two disagree,
        // "/%73uperadmin/login" is a request the login filter processes and neither defense sees.
        //
        // Deliberately sent to a tenant subdomain, not the bare base domain: on the bare domain
        // TenantResolutionFilter (which excludes "/superadmin" by the same raw-URI prefix, so it
        // does not exclude this) rejects the request with its own 404 and hides the question. A
        // real tenant host resolves, and the request reaches the security chain exactly as it
        // would in production.
        String slug = "acme" + shortId();
        seedTenant(slug);
        MockHttpSession session = new MockHttpSession();

        mockMvc
                .perform(
                        post(URI.create("http://" + slug + ".localhost/%73uperadmin/login"))
                                .param("email", superAdminEmail)
                                .param("password", PASSWORD)
                                .with(csrf())
                                .header("X-Forwarded-For", OUTSIDE_IP)
                                .session(session))
                .andExpect(status().isForbidden());

        assertThat(session.getAttribute(SUPER_ADMIN_SECURITY_CONTEXT_KEY)).isNull();
    }

    @Test
    void superAdminLogin_afterTenAttemptsInOneMinute_isRateLimited() throws Exception {
        for (int attempt = 0; attempt < 10; attempt++) {
            mockMvc.perform(superAdminLoginRequest("spray-" + attempt + "@caderly.test", "Whatever123"));
        }

        mockMvc
                .perform(superAdminLoginRequest("spray-final@caderly.test", "Whatever123"))
                .andExpect(redirectedUrl("/superadmin/login?rateLimited"));
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder
            superAdminLoginRequest(String email, String password) {
        return post(URI.create("http://localhost/superadmin/login"))
                .param("email", email)
                .param("password", password)
                .with(csrf());
    }

    private MockHttpSession superAdminSession() throws Exception {
        return (MockHttpSession)
                mockMvc
                        .perform(superAdminLoginRequest(superAdminEmail, PASSWORD))
                        .andExpect(redirectedUrl("/superadmin/tenants"))
                        .andReturn()
                        .getRequest()
                        .getSession();
    }

    private MockHttpSession tenantSession(String slug, String email) throws Exception {
        return (MockHttpSession)
                mockMvc
                        .perform(
                                post(URI.create("http://" + slug + ".localhost/login"))
                                        .param("email", email)
                                        .param("password", PASSWORD)
                                        .with(csrf()))
                        .andExpect(redirectedUrl("/"))
                        .andReturn()
                        .getRequest()
                        .getSession();
    }

    /**
     * Each test seeds a uniquely-suffixed address, so filtering by it isolates this attempt's rows
     * from every other test's without truncating a shared table.
     */
    private LoginAudit onlyAuditRowFor(String email) {
        List<LoginAudit> rows =
                asSystem(
                        () ->
                                transactions.execute(
                                        status ->
                                                loginAudits.findAll().stream()
                                                        .filter(row -> email.equals(row.emailAttempted()))
                                                        .toList()));
        assertThat(rows).hasSize(1);
        return rows.getFirst();
    }

    private UUID superAdminId() {
        return asSystem(
                () ->
                        transactions.execute(
                                status -> superAdmins.findByEmail(superAdminEmail).orElseThrow().requireId()));
    }

    private UUID seedTenant(String slug) {
        return asSystem(
                () -> transactions.execute(status -> tenants.save(new Tenant(slug, "Acme")).getId()));
    }

    private void seedAdmin(UUID tenantId, String email) {
        TenantContext.set(tenantId);
        try {
            transactions.execute(
                    status -> {
                        AppUser user = AppUser.active(email, passwordEncoder.encode(PASSWORD));
                        user.grant(Role.ADMIN);
                        return users.save(user);
                    });
        } finally {
            TenantContext.clear();
        }
    }

    private static <T> T asSystem(Supplier<T> action) {
        return TenantContext.runAsSystem("test: seed super admin realm fixtures", action);
    }

    private static String shortId() {
        return UUID.randomUUID().toString().substring(0, 8);
    }
}
