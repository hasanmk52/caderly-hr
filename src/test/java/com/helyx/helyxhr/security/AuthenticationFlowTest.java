package com.helyx.helyxhr.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.helyx.helyxhr.TestcontainersConfiguration;
import com.helyx.helyxhr.identity.AppUser;
import com.helyx.helyxhr.identity.AppUserRepository;
import com.helyx.helyxhr.identity.Role;
import com.helyx.helyxhr.identity.UserStatus;
import com.helyx.helyxhr.tenant.Tenant;
import com.helyx.helyxhr.tenant.TenantContext;
import com.helyx.helyxhr.tenant.TenantRepository;
import java.net.URI;
import java.time.Instant;
import java.util.UUID;
import java.util.function.Consumer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The authentication half of the sub-phase 1.2 Definition of Done, driven through the real filter
 * chain: tenant resolution, rate limiting, CSRF, form login, lockout.
 *
 * <p>Named {@code ...Test} rather than {@code ...IT} deliberately — this project has no failsafe
 * execution configured, so surefire is what runs everything and an {@code IT} suffix would mean
 * the test silently never runs.
 */
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("test")
@SpringBootTest
@AutoConfigureMockMvc
class AuthenticationFlowTest {

    private static final String PASSWORD = "C0rrectHorseBattery";

    @Autowired private MockMvc mockMvc;
    @Autowired private TenantRepository tenants;
    @Autowired private AppUserRepository users;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private TransactionTemplate transactions;
    @Autowired private RateLimitFilter rateLimitFilter;

    private String slugA;
    private String slugB;
    private String emailInA;

    @BeforeEach
    void seed() {
        // Every case in this class posts to /login from the same client address, and the limit
        // is 10/min/IP. Without this, later tests would be redirected to ?rateLimited and fail
        // for a reason that has nothing to do with what they assert.
        rateLimitFilter.clearBuckets(null);

        slugA = "acme" + shortId();
        slugB = "other" + shortId();
        UUID tenantA = seedTenant(slugA, "Acme");
        seedTenant(slugB, "Other Co");

        emailInA = "user-" + shortId() + "@example.test";
        seedUser(tenantA, emailInA, user -> user.grant(Role.EMPLOYEE));
    }

    // ---------- login ----------

    @Test
    void login_whenValidCredentials_authenticatesAndRedirectsHome() throws Exception {
        mockMvc
                .perform(loginRequest(slugA, emailInA, PASSWORD))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/"));
    }

    @Test
    void login_whenUserBelongsToAnotherTenant_failsAsUserNotFound() throws Exception {
        // The headline DoD item. The account exists and the password is correct — it is only the
        // subdomain that is wrong, and the response is indistinguishable from a typo'd address.
        mockMvc
                .perform(loginRequest(slugB, emailInA, PASSWORD))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/login?error"));
    }

    @Test
    void login_whenWrongPassword_isRejectedWithTheSameResponseAsAnUnknownUser() throws Exception {
        String unknownUserRedirect =
                mockMvc
                        .perform(loginRequest(slugA, "nobody-" + shortId() + "@example.test", PASSWORD))
                        .andReturn()
                        .getResponse()
                        .getRedirectedUrl();

        mockMvc
                .perform(loginRequest(slugA, emailInA, "WrongPassword123"))
                .andExpect(redirectedUrl(unknownUserRedirect));
    }

    @Test
    void login_whenUserIsDisabled_isRejected() throws Exception {
        UUID tenantId = tenantIdOf(slugA);
        String disabled = "disabled-" + shortId() + "@example.test";
        seedUser(tenantId, disabled, user -> {
            user.grant(Role.EMPLOYEE);
            user.disable();
        });

        mockMvc
                .perform(loginRequest(slugA, disabled, PASSWORD))
                .andExpect(redirectedUrl("/login?error"));
    }

    @Test
    void login_whenUserIsStillInvited_isRejected() throws Exception {
        UUID tenantId = tenantIdOf(slugA);
        String invited = "invited-" + shortId() + "@example.test";
        asTenant(
                tenantId,
                () ->
                        transactions.execute(
                                status ->
                                        users.save(
                                                AppUser.invited(
                                                        invited, "hash", Instant.now().plusSeconds(3600)))));

        mockMvc
                .perform(loginRequest(slugA, invited, PASSWORD))
                .andExpect(redirectedUrl("/login?error"));
    }

    @Test
    void login_whenTenantIsSuspended_isRejectedBeforeAuthentication() throws Exception {
        // PRD §20.7. Handled entirely by TenantResolutionFilter, which runs ahead of the security
        // chain — so a suspended tenant never reaches a password check at all.
        String suspendedSlug = "frozen" + shortId();
        UUID suspendedId = seedTenant(suspendedSlug, "Frozen Co");
        seedUser(suspendedId, "someone@frozen.test", user -> user.grant(Role.EMPLOYEE));
        TenantContext.runAsSystem(
                "test: suspend tenant",
                () ->
                        transactions.execute(
                                status -> {
                                    Tenant tenant = tenants.findById(suspendedId).orElseThrow();
                                    tenant.suspend();
                                    return tenants.save(tenant);
                                }));

        mockMvc
                .perform(loginRequest(suspendedSlug, "someone@frozen.test", PASSWORD))
                .andExpect(status().isServiceUnavailable());
    }

    // ---------- lockout ----------

    @Test
    void login_afterFiveFailuresInTheWindow_locksTheAccountEvenForTheCorrectPassword()
            throws Exception {
        for (int attempt = 0; attempt < 5; attempt++) {
            mockMvc
                    .perform(loginRequest(slugA, emailInA, "WrongPassword" + attempt))
                    .andExpect(redirectedUrl("/login?error"));
        }

        // The password is right this time; the lock is what refuses it.
        mockMvc
                .perform(loginRequest(slugA, emailInA, PASSWORD))
                .andExpect(redirectedUrl("/login?error"));

        AppUser locked = asTenant(tenantIdOf(slugA), () -> users.findByEmail(emailInA)).orElseThrow();
        assertThat(locked.status()).isEqualTo(UserStatus.LOCKED);
        assertThat(locked.isLocked(Instant.now())).isTrue();
    }

    @Test
    void login_afterFourFailuresThenSuccess_resetsTheCounter() throws Exception {
        for (int attempt = 0; attempt < 4; attempt++) {
            mockMvc.perform(loginRequest(slugA, emailInA, "WrongPassword" + attempt));
        }

        mockMvc.perform(loginRequest(slugA, emailInA, PASSWORD)).andExpect(redirectedUrl("/"));

        AppUser user = asTenant(tenantIdOf(slugA), () -> users.findByEmail(emailInA)).orElseThrow();
        assertThat(user.failedLoginCount()).isZero();
        assertThat(user.lastLoginAt()).isNotNull();
    }

    // ---------- CSRF ----------

    @Test
    void login_withoutCsrfToken_isForbidden() throws Exception {
        mockMvc
                .perform(
                        post(URI.create("http://" + slugA + ".localhost/login"))
                                .param("email", emailInA)
                                .param("password", PASSWORD))
                .andExpect(status().isForbidden());
    }

    @Test
    void logout_withoutCsrfToken_isForbidden() throws Exception {
        mockMvc
                .perform(post(URI.create("http://" + slugA + ".localhost/logout")))
                .andExpect(status().isForbidden());
    }

    // ---------- rate limiting ----------

    @Test
    void login_afterTenAttemptsInOneMinute_isRateLimited() throws Exception {
        for (int attempt = 0; attempt < 10; attempt++) {
            mockMvc.perform(loginRequest(slugA, "spray-" + attempt + "@example.test", "Whatever123"));
        }

        mockMvc
                .perform(loginRequest(slugA, "spray-final@example.test", "Whatever123"))
                .andExpect(redirectedUrl("/login?rateLimited"));
    }

    // ---------- helpers ----------

    private MockHttpServletRequestBuilder loginRequest(String slug, String email, String password) {
        return post(URI.create("http://" + slug + ".localhost/login"))
                .param("email", email)
                .param("password", password)
                .with(csrf());
    }

    private static String shortId() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    private UUID seedTenant(String slug, String name) {
        return TenantContext.runAsSystem(
                "test: seed tenant",
                () -> transactions.execute(status -> tenants.save(new Tenant(slug, name)).getId()));
    }

    private UUID tenantIdOf(String slug) {
        return TenantContext.runAsSystem(
                "test: look up tenant",
                () -> tenants.findBySlugAndDeletedAtIsNull(slug).orElseThrow().getId());
    }

    private void seedUser(UUID tenantId, String email, Consumer<AppUser> customise) {
        asTenant(
                tenantId,
                () ->
                        transactions.execute(
                                status -> {
                                    AppUser user = AppUser.active(email, passwordEncoder.encode(PASSWORD));
                                    customise.accept(user);
                                    return users.save(user);
                                }));
    }

    private <T> T asTenant(UUID tenantId, java.util.function.Supplier<T> action) {
        TenantContext.set(tenantId);
        try {
            return action.get();
        } finally {
            TenantContext.clear();
        }
    }
}
