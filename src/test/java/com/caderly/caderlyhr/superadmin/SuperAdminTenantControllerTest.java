package com.caderly.caderlyhr.superadmin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.caderly.caderlyhr.TestcontainersConfiguration;
import com.caderly.caderlyhr.audit.AuditEntryRepository;
import com.caderly.caderlyhr.audit.system.AuditEntry;
import com.caderly.caderlyhr.audit.system.AuditEntry.Action;
import com.caderly.caderlyhr.identity.AppUser;
import com.caderly.caderlyhr.identity.AppUserRepository;
import com.caderly.caderlyhr.identity.Role;
import com.caderly.caderlyhr.identity.UserStatus;
import com.caderly.caderlyhr.notifications.system.EmailOutboxRepository;
import com.caderly.caderlyhr.security.RateLimitFilter;
import com.caderly.caderlyhr.tenant.Tenant;
import com.caderly.caderlyhr.tenant.TenantContext;
import com.caderly.caderlyhr.tenant.TenantFacade;
import com.caderly.caderlyhr.tenant.TenantRepository;
import java.net.URI;
import java.util.Comparator;
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
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * {@code SuperAdminTenantController} — the console's create/suspend/delete/impersonate flows (PRD
 * FR-1.8, Phase 1.13 Task 6). Authenticates as a real {@code SuperAdmin} via {@code
 * /superadmin/login} rather than mocking {@link SuperAdminPrincipal} directly (mirroring {@code
 * SuperAdminSecurityConfigTest}), since the whole point of several cases here is that the realm
 * boundary itself — not a stubbed principal — is what a plain tenant Admin session or an
 * unauthenticated request runs into.
 */
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("test")
@SpringBootTest
@AutoConfigureMockMvc
class SuperAdminTenantControllerTest {

    private static final String PASSWORD = "C0rrectHorseBattery";

    @Autowired private MockMvc mockMvc;
    @Autowired private SuperAdminRepository superAdmins;
    @Autowired private TenantRepository tenantRepository;
    @Autowired private TenantFacade tenantFacade;
    @Autowired private AppUserRepository users;
    @Autowired private EmailOutboxRepository outbox;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private TransactionTemplate transactions;
    @Autowired private RateLimitFilter rateLimitFilter;
    @Autowired private AuditEntryRepository auditEntries;

    private String superAdminEmail;
    private UUID superAdminId;

    @BeforeEach
    void seed() {
        rateLimitFilter.clearBuckets(null);
        superAdminEmail = "root-" + shortId() + "@caderly.test";
        superAdminId =
                asSystem(
                                () ->
                                        transactions.execute(
                                                status ->
                                                        superAdmins.save(
                                                                new SuperAdmin(
                                                                        superAdminEmail, passwordEncoder.encode(PASSWORD)))))
                        .requireId();
    }

    @Test
    void list_asSuperAdmin_returns200() throws Exception {
        MockHttpSession session = superAdminSession();

        mockMvc
                .perform(get(URI.create("http://localhost/superadmin/tenants")).session(session))
                .andExpect(status().isOk());
    }

    @Test
    void list_asATenantAdmin_isNotAuthenticatedInThisRealm() throws Exception {
        String slug = "acme" + shortId();
        UUID tenantId = seedTenant(slug);
        String email = "admin-" + shortId() + "@example.test";
        seedActiveAdmin(tenantId, email);
        MockHttpSession tenantSession = tenantAdminSession(slug, email);

        // Same reasoning as SuperAdminSecurityConfigTest: this realm keeps its SecurityContext
        // under its own session key, so a tenant session looks anonymous here rather than
        // authenticated-with-the-wrong-role — a redirect to this realm's own login, not a 403.
        mockMvc
                .perform(get(URI.create("http://localhost/superadmin/tenants")).session(tenantSession))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/superadmin/login"));
    }

    @Test
    void list_whenUnauthenticated_redirectsToSuperAdminLogin() throws Exception {
        mockMvc
                .perform(get(URI.create("http://localhost/superadmin/tenants")))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/superadmin/login"));
    }

    @Test
    void create_withValidData_createsTheTenantAndQueuesTheFirstAdminsInvite() throws Exception {
        MockHttpSession session = superAdminSession();
        String slug = uniqueSlug();
        String adminEmail = uniqueEmail();

        mockMvc
                .perform(createRequest(session, slug, adminEmail))
                .andExpect(redirectedUrl("/superadmin/tenants"));

        Tenant saved = findTenantBySlug(slug);
        assertThat(saved.getName()).isEqualTo("Acme Inc");

        TenantContext.set(saved.getId());
        try {
            AppUser invited = users.findByEmail(adminEmail).orElseThrow();
            assertThat(invited.status()).isEqualTo(UserStatus.INVITED);
            assertThat(invited.roles()).containsExactly(Role.ADMIN);
        } finally {
            TenantContext.clear();
        }

        boolean queued =
                asSystem(
                        () ->
                                transactions.execute(
                                        status ->
                                                outbox.findAll().stream()
                                                        .anyMatch(row -> row.toEmail().equals(adminEmail))));
        assertThat(queued).isTrue();

        AuditEntry audit = onlyTenantAuditRow(saved.getId(), Action.CREATE);
        assertThat(audit.actorUserId()).isEqualTo(superAdminId);
        assertThat(audit.actorRole()).isEqualTo("SUPER_ADMIN");
        assertThat(audit.afterJson()).contains(slug).contains("Acme Inc");
    }

    @Test
    void create_withADuplicateSlug_reRendersTheFormWithAnErrorAndCreatesNoSecondRow() throws Exception {
        MockHttpSession session = superAdminSession();
        String slug = uniqueSlug();
        mockMvc.perform(createRequest(session, slug, uniqueEmail())).andExpect(redirectedUrl("/superadmin/tenants"));

        mockMvc.perform(createRequest(session, slug, uniqueEmail())).andExpect(status().isOk());

        long matching =
                asSystem(
                        () ->
                                transactions.execute(
                                        status ->
                                                tenantRepository.findAll().stream()
                                                        .filter(t -> t.getSlug().equals(slug))
                                                        .count()));
        assertThat(matching).isEqualTo(1);
    }

    /**
     * Proves the fragment-response fix, not just the underlying state change: a real htmx PATCH
     * never follows a redirect (there is none), and the response body it receives is the
     * re-rendered {@code #tenant-list-content} fragment showing the new status text. Asserting
     * {@code status().isOk()} here is deliberate — it is exactly the assertion that would have
     * failed against the previous {@code redirect:} + {@code HX-Redirect} implementation, which
     * returned a 302 with no body for MockMvc (which never follows redirects) to inspect.
     */
    @Test
    void suspend_togglesSuspensionBothWays() throws Exception {
        MockHttpSession session = superAdminSession();
        String slug = "acme" + shortId();
        UUID tenantId = seedTenant(slug);

        mockMvc
                .perform(
                        patch(URI.create("http://localhost/superadmin/tenants/" + tenantId + "/suspend"))
                                .session(session)
                                .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Suspended")));
        assertThat(findTenantById(tenantId).isSuspended()).isTrue();
        AuditEntry suspendRow = tenantAuditRows(tenantId, Action.UPDATE).getFirst();
        assertThat(suspendRow.actorUserId()).isEqualTo(superAdminId);
        assertThat(suspendRow.afterJson()).contains("\"suspended\"").contains("true");

        mockMvc
                .perform(
                        patch(URI.create("http://localhost/superadmin/tenants/" + tenantId + "/suspend"))
                                .session(session)
                                .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Active")));
        assertThat(findTenantById(tenantId).isSuspended()).isFalse();
        List<AuditEntry> updateRows =
                tenantAuditRows(tenantId, Action.UPDATE).stream()
                        .sorted(Comparator.comparing(AuditEntry::occurredAt))
                        .toList();
        assertThat(updateRows).hasSize(2);
        assertThat(updateRows.get(1).afterJson()).contains("\"suspended\"").contains("false");
    }

    /** See {@link #suspend_togglesSuspensionBothWays}'s Javadoc — same fragment-response proof. */
    @Test
    void delete_softDeletesTheTenant_stillListedButUnresolvableBySlug() throws Exception {
        MockHttpSession session = superAdminSession();
        String slug = "acme" + shortId();
        UUID tenantId = seedTenant(slug);

        mockMvc
                .perform(delete(URI.create("http://localhost/superadmin/tenants/" + tenantId)).session(session).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Deleted")));

        assertThat(findTenantById(tenantId).getDeletedAt()).isNotNull();
        assertThat(tenantFacade.bySlug(slug)).isEmpty();
        assertThat(tenantFacade.listAllForAdmin().stream().anyMatch(row -> row.id().equals(tenantId))).isTrue();

        AuditEntry deleteRow = onlyTenantAuditRow(tenantId, Action.DELETE);
        assertThat(deleteRow.actorUserId()).isEqualTo(superAdminId);
        assertThat(deleteRow.actorRole()).isEqualTo("SUPER_ADMIN");
        assertThat(deleteRow.afterJson()).contains("\"deleted\"").contains("true");
    }

    @Test
    void impersonate_withAnActiveAdmin_redirectsToTheTenantSubdomainWithAToken() throws Exception {
        MockHttpSession session = superAdminSession();
        String slug = "acme" + shortId();
        UUID tenantId = seedTenant(slug);
        seedActiveAdmin(tenantId, uniqueEmail());

        var result =
                mockMvc
                        .perform(
                                post(URI.create("http://localhost/superadmin/tenants/" + tenantId + "/impersonate"))
                                        .session(session)
                                        .with(csrf()))
                        .andReturn();

        String location = result.getResponse().getRedirectedUrl();
        assertThat(location).isNotNull();
        assertThat(location).startsWith("https://" + slug + ".localhost/impersonate?token=");
    }

    @Test
    void impersonate_withNoActiveAdmin_redirectsBackWithAnErrorAndMintsNoTicket() throws Exception {
        MockHttpSession session = superAdminSession();
        String slug = "acme" + shortId();
        UUID tenantId = seedTenant(slug);
        // No Admin seeded at all this time.

        var result =
                mockMvc
                        .perform(
                                post(URI.create("http://localhost/superadmin/tenants/" + tenantId + "/impersonate"))
                                        .session(session)
                                        .with(csrf()))
                        .andReturn();

        // Proof that nothing was minted: a minted ticket always redirects to the tenant's own
        // subdomain with a token query parameter (see the success case above) — landing back on
        // this console's own tenants page instead is only possible if mint() was never called.
        assertThat(result.getResponse().getRedirectedUrl()).isEqualTo("/superadmin/tenants?impersonateFailed");
    }

    private MockHttpServletRequestBuilder createRequest(MockHttpSession session, String slug, String adminEmail) {
        return post(URI.create("http://localhost/superadmin/tenants"))
                .session(session)
                .with(csrf())
                .param("slug", slug)
                .param("name", "Acme Inc")
                .param("timezone", "UTC")
                .param("weekendDays", "96")
                .param("firstAdminEmail", adminEmail);
    }

    private MockHttpSession superAdminSession() throws Exception {
        return (MockHttpSession)
                mockMvc
                        .perform(
                                post(URI.create("http://localhost/superadmin/login"))
                                        .param("email", superAdminEmail)
                                        .param("password", PASSWORD)
                                        .with(csrf()))
                        .andExpect(redirectedUrl("/superadmin/tenants"))
                        .andReturn()
                        .getRequest()
                        .getSession();
    }

    private MockHttpSession tenantAdminSession(String slug, String email) throws Exception {
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

    private Tenant findTenantById(UUID tenantId) {
        return asSystem(() -> transactions.execute(status -> tenantRepository.findById(tenantId).orElseThrow()));
    }

    private Tenant findTenantBySlug(String slug) {
        return asSystem(
                () ->
                        transactions.execute(
                                status ->
                                        tenantRepository.findAll().stream()
                                                .filter(t -> t.getSlug().equals(slug))
                                                .findFirst()
                                                .orElseThrow()));
    }

    private UUID seedTenant(String slug) {
        return asSystem(() -> transactions.execute(status -> tenantRepository.save(new Tenant(slug, "Acme")).getId()));
    }

    private void seedActiveAdmin(UUID tenantId, String email) {
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

    private AuditEntry onlyTenantAuditRow(UUID tenantId, Action action) {
        List<AuditEntry> rows = tenantAuditRows(tenantId, action);
        assertThat(rows).hasSize(1);
        return rows.getFirst();
    }

    private List<AuditEntry> tenantAuditRows(UUID tenantId, Action action) {
        return asSystem(
                () ->
                        transactions.execute(
                                status ->
                                        auditEntries.findAll().stream()
                                                .filter(row -> "Tenant".equals(row.entityType()))
                                                .filter(row -> tenantId.toString().equals(row.entityId()))
                                                .filter(row -> row.action() == action)
                                                .toList()));
    }

    private static <T> T asSystem(Supplier<T> action) {
        return TenantContext.runAsSystem("test: seed super admin tenant controller fixtures", action);
    }

    private static String uniqueSlug() {
        return "sa-test-" + UUID.randomUUID().toString().substring(0, 8);
    }

    private static String uniqueEmail() {
        return "admin-" + UUID.randomUUID() + "@example.test";
    }

    private static String shortId() {
        return UUID.randomUUID().toString().substring(0, 8);
    }
}
