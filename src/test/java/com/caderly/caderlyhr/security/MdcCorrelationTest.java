package com.caderly.caderlyhr.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.caderly.caderlyhr.TestcontainersConfiguration;
import com.caderly.caderlyhr.common.MdcKeys;
import com.caderly.caderlyhr.identity.AppUser;
import com.caderly.caderlyhr.identity.AppUserRepository;
import com.caderly.caderlyhr.identity.Role;
import com.caderly.caderlyhr.tenant.Tenant;
import com.caderly.caderlyhr.tenant.TenantContext;
import com.caderly.caderlyhr.tenant.TenantRepository;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URI;
import java.util.UUID;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.core.Ordered;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * CLAUDE.md §6 A09 / ADR 0017: every log line during a request should carry {@code requestId}/
 * {@code tenantId}/{@code actorId} via MDC. A test-only filter registered after everything else in
 * the real chain (including {@code security.ActorMdcFilter}) snapshots the MDC map at the point a
 * controller would see it — the most direct way to prove the values are actually there without
 * parsing structured JSON log output (disabled in the test profile; see application-test.yml).
 *
 * <p>The authenticated case drives a real login (like {@code AuthenticationFlowTest}), not
 * {@code SecurityMockMvcRequestPostProcessors.user(...)}: that shortcut installs Spring Security
 * Test's own generic {@code User} principal, which is not an {@code identity.AppUserPrincipal} —
 * {@code ActorMdcFilter}'s {@code instanceof AuditActor} check would never see a real actor.
 */
@Import({TestcontainersConfiguration.class, MdcCorrelationTest.SnapshotFilterConfig.class})
@ActiveProfiles("test")
@SpringBootTest
@AutoConfigureMockMvc
class MdcCorrelationTest {

    private static final String PASSWORD = "C0rrectHorseBattery";

    @Autowired private MockMvc mockMvc;
    @Autowired private TenantRepository tenants;
    @Autowired private AppUserRepository users;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private TransactionTemplate transactions;
    @Autowired private MdcSnapshot snapshot;

    private String slug;
    private UUID tenantId;

    @BeforeEach
    void seed() {
        snapshot.clear();
        slug = "mdc" + UUID.randomUUID().toString().substring(0, 8);
        tenantId =
                TenantContext.runAsSystem(
                        "test: seed tenant",
                        () -> transactions.execute(status -> tenants.save(new Tenant(slug, "MDC Co")).getId()));
    }

    @Test
    void authenticatedRequest_populatesRequestIdTenantIdAndActorId() throws Exception {
        String email = "someone-" + UUID.randomUUID().toString().substring(0, 8) + "@mdc.test";
        asTenant(
                tenantId,
                () ->
                        transactions.execute(
                                status -> {
                                    AppUser user = AppUser.active(email, passwordEncoder.encode(PASSWORD));
                                    user.grant(Role.EMPLOYEE);
                                    return users.save(user);
                                }));

        MockHttpSession session =
                (MockHttpSession)
                        mockMvc
                                .perform(
                                        post(URI.create("http://" + slug + ".localhost/login"))
                                                .param("email", email)
                                                .param("password", PASSWORD)
                                                .with(csrf()))
                                .andReturn()
                                .getRequest()
                                .getSession();

        mockMvc.perform(get(URI.create("http://" + slug + ".localhost/")).session(session));

        assertThat(snapshot.requestId).isNotBlank();
        assertThat(snapshot.tenantId).isEqualTo(tenantId.toString());
        assertThat(snapshot.actorId).isNotBlank();
    }

    @Test
    void anonymousRequest_populatesTenantIdButNoActorId() throws Exception {
        mockMvc.perform(get(URI.create("http://" + slug + ".localhost/login")));

        assertThat(snapshot.tenantId).isEqualTo(tenantId.toString());
        assertThat(snapshot.actorId).isNull();
    }

    @Test
    void afterTheRequestCompletes_mdcIsClearedFromTheHandlingThread() throws Exception {
        mockMvc.perform(get(URI.create("http://" + slug + ".localhost/login")));

        // Thread-pooled request handling means this only proves no leak *within* one request's
        // thread — TenantResolutionFilter's own finally block is what matters, and it already ran
        // by the time this assertion executes on the test's own thread.
        assertThat(MDC.get(MdcKeys.REQUEST_ID)).isNull();
        assertThat(MDC.get(MdcKeys.TENANT_ID)).isNull();
        assertThat(MDC.get(MdcKeys.ACTOR_ID)).isNull();
    }

    private <T> T asTenant(UUID id, Supplier<T> action) {
        TenantContext.set(id);
        try {
            return action.get();
        } finally {
            TenantContext.clear();
        }
    }

    /** Captured by {@link SnapshotFilter}, which runs last in the chain (after ActorMdcFilter). */
    static class MdcSnapshot {
        private volatile @Nullable String requestId;
        private volatile @Nullable String tenantId;
        private volatile @Nullable String actorId;

        void clear() {
            requestId = null;
            tenantId = null;
            actorId = null;
        }
    }

    static class SnapshotFilter extends OncePerRequestFilter {

        private final MdcSnapshot snapshot;

        SnapshotFilter(MdcSnapshot snapshot) {
            this.snapshot = snapshot;
        }

        @Override
        protected void doFilterInternal(
                HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
                throws ServletException, IOException {
            snapshot.requestId = MDC.get(MdcKeys.REQUEST_ID);
            snapshot.tenantId = MDC.get(MdcKeys.TENANT_ID);
            snapshot.actorId = MDC.get(MdcKeys.ACTOR_ID);
            filterChain.doFilter(request, response);
        }
    }

    @TestConfiguration
    static class SnapshotFilterConfig {

        @Bean
        MdcSnapshot mdcSnapshot() {
            return new MdcSnapshot();
        }

        @Bean
        FilterRegistrationBean<SnapshotFilter> snapshotFilterRegistration(MdcSnapshot snapshot) {
            FilterRegistrationBean<SnapshotFilter> registration =
                    new FilterRegistrationBean<>(new SnapshotFilter(snapshot));
            // Last in the chain — after tenant resolution, rate limiting, Spring Security, and
            // ActorMdcFilter — so every MDC key this phase adds is already set by the time it runs.
            registration.setOrder(Ordered.LOWEST_PRECEDENCE);
            return registration;
        }
    }
}
