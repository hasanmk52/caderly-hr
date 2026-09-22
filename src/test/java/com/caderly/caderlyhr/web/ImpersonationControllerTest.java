package com.caderly.caderlyhr.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.caderly.caderlyhr.TestcontainersConfiguration;
import com.caderly.caderlyhr.audit.AuditEntryRepository;
import com.caderly.caderlyhr.audit.system.AuditEntry;
import com.caderly.caderlyhr.audit.system.AuditEntry.Action;
import com.caderly.caderlyhr.identity.AppUser;
import com.caderly.caderlyhr.identity.AppUserRepository;
import com.caderly.caderlyhr.identity.ImpersonationService;
import com.caderly.caderlyhr.identity.PasswordChangedEvent;
import com.caderly.caderlyhr.identity.Role;
import com.caderly.caderlyhr.superadmin.SuperAdmin;
import com.caderly.caderlyhr.superadmin.SuperAdminRepository;
import com.caderly.caderlyhr.support.MutableClock;
import com.caderly.caderlyhr.support.MutableClockConfiguration;
import com.caderly.caderlyhr.tenant.Tenant;
import com.caderly.caderlyhr.tenant.TenantContext;
import com.caderly.caderlyhr.tenant.TenantRepository;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The impersonation hand-off end to end (PRD FR-1.8): a ticket minted in the Super Admin realm is
 * redeemed on the tenant's own subdomain, inside the tenant-facing security chain, and the session
 * it leaves behind is an ordinary Admin session — except that every write it makes is tagged as
 * impersonated and the operator can end it.
 *
 * <p>The cases that matter most here are the ones a status-code assertion alone would miss: that
 * the established context lands under the key the <em>tenant</em> chain reads (and not the Super
 * Admin realm's), and that a ticket minted for one tenant is refused on another's subdomain.
 */
@Import({TestcontainersConfiguration.class, MutableClockConfiguration.class})
@ActiveProfiles("test")
@SpringBootTest
@AutoConfigureMockMvc
class ImpersonationControllerTest {

    private static final String PASSWORD = "C0rrectHorseBattery";
    private static final String FAILURE_URL = "/login?impersonationFailed";
    private static final String OUTSIDE_IP = "198.51.100.7";

    @Autowired private MockMvc mockMvc;
    @Autowired private TenantRepository tenants;
    @Autowired private AppUserRepository users;
    @Autowired private SuperAdminRepository superAdmins;
    @Autowired private AuditEntryRepository auditEntries;
    @Autowired private ImpersonationService impersonation;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private TransactionTemplate transactions;
    @Autowired private ApplicationEventPublisher events;
    @Autowired private MutableClock clock;

    private String slug;
    private UUID tenantId;
    private UUID adminUserId;
    private String adminEmail;
    private UUID superAdminId;
    private String superAdminEmail;

    @BeforeEach
    void seed() {
        slug = "imp" + shortId();
        tenantId = seedTenant(slug);
        adminEmail = "admin-" + shortId() + "@example.test";
        adminUserId = seedAdmin(tenantId, adminEmail);
        superAdminEmail = "root-" + shortId() + "@caderly.test";
        superAdminId =
                asSystem(
                                () ->
                                        transactions.execute(
                                                status ->
                                                        superAdmins.save(
                                                                new SuperAdmin(superAdminEmail, passwordEncoder.encode(PASSWORD)))))
                        .requireId();
    }

    @Test
    void redeem_withAValidTicket_establishesAnAdminSessionOnTheTenantSubdomain() throws Exception {
        MockHttpSession session = redeemSuccessfully();

        // The proof that matters: the very next request, on the same subdomain, is authorized as
        // that Admin by the ordinary tenant chain — nothing about this session is special-cased.
        mockMvc
                .perform(get(URI.create("http://" + slug + ".localhost/admin/users")).session(session))
                .andExpect(status().isOk());
    }

    @Test
    void redeem_storesTheContextUnderTheTenantChainsOwnSessionKey() throws Exception {
        // The Super Admin realm keeps its context under SUPERADMIN_SECURITY_CONTEXT so the two
        // realms cannot see each other (see SuperAdminSecurityConfigTest). This endpoint runs in
        // the *tenant* chain, so it has to write the tenant chain's default key — saving under
        // the operator realm's key would leave the impersonated session silently unauthenticated
        // here and, worse, hand a tenant principal to /superadmin/**.
        MockHttpSession session = redeemSuccessfully();

        assertThat(session.getAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY))
                .isNotNull();
        assertThat(session.getAttribute("SUPERADMIN_SECURITY_CONTEXT")).isNull();
    }

    @Test
    void redeem_withATicketMintedForAnotherTenant_isRefusedAndEstablishesNoSession() throws Exception {
        String otherSlug = "imp" + shortId();
        UUID otherTenantId = seedTenant(otherSlug);
        String token = impersonation.mint(superAdminId, superAdminEmail, otherTenantId, adminUserId);

        MvcResult result =
                mockMvc.perform(redeemRequest(slug, token)).andExpect(redirectedUrl(FAILURE_URL)).andReturn();

        assertThat(authenticatedSessionOf(result)).isNull();
    }

    @Test
    void redeem_withAnAlreadyRedeemedTicket_isRefused() throws Exception {
        String token = impersonation.mint(superAdminId, superAdminEmail, tenantId, adminUserId);
        mockMvc.perform(redeemRequest(slug, token)).andExpect(redirectedUrl("/"));

        MvcResult result =
                mockMvc.perform(redeemRequest(slug, token)).andExpect(redirectedUrl(FAILURE_URL)).andReturn();

        assertThat(authenticatedSessionOf(result)).isNull();
    }

    @Test
    void redeem_withAnExpiredTicket_isRefused() throws Exception {
        String token = impersonation.mint(superAdminId, superAdminEmail, tenantId, adminUserId);
        clock.advance(Duration.ofSeconds(61));

        MvcResult result =
                mockMvc.perform(redeemRequest(slug, token)).andExpect(redirectedUrl(FAILURE_URL)).andReturn();

        assertThat(authenticatedSessionOf(result)).isNull();
    }

    @Test
    void redeem_withNoTokenParameterAtAll_isRefusedWithTheSameRedirect() throws Exception {
        // A required @RequestParam would answer 400 here, and a 400 is the one response that tells
        // a prober the endpoint exists. Every rejection has to look like every other rejection.
        MvcResult result =
                mockMvc
                        .perform(get(URI.create("http://" + slug + ".localhost/impersonate")))
                        .andExpect(redirectedUrl(FAILURE_URL))
                        .andReturn();

        assertThat(authenticatedSessionOf(result)).isNull();
    }

    @Test
    void redeem_withAnUnknownToken_isRefused() throws Exception {
        MvcResult result =
                mockMvc
                        .perform(redeemRequest(slug, "not-a-token"))
                        .andExpect(redirectedUrl(FAILURE_URL))
                        .andReturn();

        assertThat(authenticatedSessionOf(result)).isNull();
    }

    @Test
    void redeem_fromAnAddressOutsideTheSuperAdminAllowlist_stillWorks() throws Exception {
        // The redeem endpoint is a *tenant* URL: the Admin's own browser follows this link from
        // wherever they are, and the operator IP allowlist that guards /superadmin/** must not
        // apply to it. This is also why the path deliberately does not start with "/superadmin"
        // — see ImpersonationController's javadoc.
        String token = impersonation.mint(superAdminId, superAdminEmail, tenantId, adminUserId);

        mockMvc
                .perform(redeemRequest(slug, token).header("X-Forwarded-For", OUTSIDE_IP))
                .andExpect(redirectedUrl("/"));
    }

    @Test
    void redeem_writesAnImpersonationStartAuditEntry() throws Exception {
        redeemSuccessfully();

        AuditEntry start = onlyImpersonationRow(Action.CREATE);
        assertThat(start.tenantId()).isEqualTo(tenantId);
        assertThat(start.actorUserId()).isEqualTo(superAdminId);
        assertThat(start.actorRole()).isEqualTo("SUPER_ADMIN");
        assertThat(start.afterJson()).contains(superAdminEmail).contains(adminEmail).contains(slug);
    }

    @Test
    void endImpersonation_invalidatesTheSessionAndPairsWithTheStartEntry() throws Exception {
        MockHttpSession session = redeemSuccessfully();

        mockMvc
                .perform(post(URI.create("http://" + slug + ".localhost/end-impersonation")).session(session).with(csrf()))
                .andExpect(redirectedUrl("/login?impersonationEnded"));

        assertThat(session.isInvalid()).isTrue();

        AuditEntry start = onlyImpersonationRow(Action.CREATE);
        AuditEntry end = onlyImpersonationRow(Action.DELETE);
        // Same correlation id on both rows so an Admin viewer can pair a session's start with
        // its end (and spot one that was never ended).
        assertThat(end.entityId()).isEqualTo(start.entityId());
        assertThat(end.actorUserId()).isEqualTo(superAdminId);
        assertThat(end.actorRole()).isEqualTo("SUPER_ADMIN");
    }

    @Test
    void passwordChangeOnTheImpersonatedAccount_terminatesTheImpersonatedSession() throws Exception {
        // PRD §19.1 / CLAUDE.md §6 A07: changing a password kills that user's live sessions. An
        // impersonated session authenticates *as* that user without going through the chain's
        // SessionAuthenticationStrategy, so unless the redeem endpoint registers it itself it is
        // invisible to SessionRevoker's registry scan and would survive the change made to shut it
        // out. This is the end-to-end proof that it does not.
        MockHttpSession session = redeemSuccessfully();
        mockMvc
                .perform(get(URI.create("http://" + slug + ".localhost/admin/users")).session(session))
                .andExpect(status().isOk());

        // The production trigger, not a direct SessionRevoker call: PasswordResetService publishes
        // this event and security.PasswordChangeSessionRevoker acts on it after commit.
        asSystem(
                () ->
                        transactions.execute(
                                status -> {
                                    events.publishEvent(new PasswordChangedEvent(adminUserId));
                                    return null;
                                }));

        // ConcurrentSessionFilter is what enforces an expired registration: it logs the session out
        // and short-circuits the chain rather than serving the page.
        String body =
                mockMvc
                        .perform(get(URI.create("http://" + slug + ".localhost/admin/users")).session(session))
                        .andReturn()
                        .getResponse()
                        .getContentAsString();

        assertThat(body).contains("This session has been expired");
        assertThat(session.isInvalid()).isTrue();
    }

    @Test
    void endImpersonation_onAnOrdinaryTenantSession_endsNothingAndWritesNoAuditEntry() throws Exception {
        MockHttpSession session = normalLoginSession();

        mockMvc
                .perform(post(URI.create("http://" + slug + ".localhost/end-impersonation")).session(session).with(csrf()))
                .andExpect(redirectedUrl("/"));

        assertThat(session.isInvalid()).isFalse();
        assertThat(impersonationRows()).isEmpty();
    }

    @Test
    void endImpersonation_whenUnauthenticated_isNotPermitted() throws Exception {
        mockMvc
                .perform(post(URI.create("http://" + slug + ".localhost/end-impersonation")).with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/login"));
    }

    @Test
    void homePage_duringAnImpersonatedSession_rendersTheBanner() throws Exception {
        MockHttpSession session = redeemSuccessfully();

        String html =
                mockMvc
                        .perform(get(URI.create("http://" + slug + ".localhost/")).session(session))
                        .andExpect(status().isOk())
                        .andReturn()
                        .getResponse()
                        .getContentAsString();

        assertThat(html).contains(superAdminEmail).contains("/end-impersonation");
    }

    @Test
    void homePage_duringAnOrdinaryTenantSession_rendersNoBanner() throws Exception {
        MockHttpSession session = normalLoginSession();

        String html =
                mockMvc
                        .perform(get(URI.create("http://" + slug + ".localhost/")).session(session))
                        .andExpect(status().isOk())
                        .andReturn()
                        .getResponse()
                        .getContentAsString();

        assertThat(html).doesNotContain("/end-impersonation");
    }

    private MockHttpSession redeemSuccessfully() throws Exception {
        String token = impersonation.mint(superAdminId, superAdminEmail, tenantId, adminUserId);
        MvcResult result = mockMvc.perform(redeemRequest(slug, token)).andExpect(redirectedUrl("/")).andReturn();
        MockHttpSession session = (MockHttpSession) result.getRequest().getSession(false);
        assertThat(session).isNotNull();
        return session;
    }

    private static MockHttpServletRequestBuilder redeemRequest(String tenantSlug, String token) {
        return get(URI.create("http://" + tenantSlug + ".localhost/impersonate")).param("token", token);
    }

    /** The session a redeem attempt left behind, or {@code null} if it authenticated nobody. */
    private static @Nullable MockHttpSession authenticatedSessionOf(MvcResult result) {
        MockHttpSession session = (MockHttpSession) result.getRequest().getSession(false);
        if (session == null
                || session.getAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY) == null) {
            return null;
        }
        return session;
    }

    private MockHttpSession normalLoginSession() throws Exception {
        return (MockHttpSession)
                mockMvc
                        .perform(
                                post(URI.create("http://" + slug + ".localhost/login"))
                                        .param("email", adminEmail)
                                        .param("password", PASSWORD)
                                        .with(csrf()))
                        .andExpect(redirectedUrl("/"))
                        .andReturn()
                        .getRequest()
                        .getSession();
    }

    private AuditEntry onlyImpersonationRow(Action action) {
        List<AuditEntry> rows = impersonationRows().stream().filter(row -> row.action() == action).toList();
        assertThat(rows).hasSize(1);
        return rows.getFirst();
    }

    /** This test's tenant is freshly seeded per test, so filtering on it isolates its own rows. */
    private List<AuditEntry> impersonationRows() {
        return asSystem(
                () ->
                        transactions.execute(
                                status ->
                                        auditEntries.findAll().stream()
                                                .filter(row -> tenantId.equals(row.tenantId()))
                                                .filter(row -> "ImpersonationSession".equals(row.entityType()))
                                                .toList()));
    }

    private UUID seedTenant(String tenantSlug) {
        return asSystem(
                () -> transactions.execute(status -> tenants.save(new Tenant(tenantSlug, "Impersonation Co")).getId()));
    }

    private UUID seedAdmin(UUID tenant, String email) {
        TenantContext.set(tenant);
        try {
            return transactions.execute(
                    status -> {
                        AppUser user = AppUser.active(email, passwordEncoder.encode(PASSWORD));
                        user.grant(Role.ADMIN);
                        return users.save(user).getId();
                    });
        } finally {
            TenantContext.clear();
        }
    }

    private static <T> T asSystem(Supplier<T> action) {
        return TenantContext.runAsSystem("test: seed impersonation fixtures", action);
    }

    private static String shortId() {
        return UUID.randomUUID().toString().substring(0, 8);
    }
}
