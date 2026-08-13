package com.helyx.helyxhr.web;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.helyx.helyxhr.TestcontainersConfiguration;
import com.helyx.helyxhr.tenant.Tenant;
import com.helyx.helyxhr.tenant.TenantContext;
import com.helyx.helyxhr.tenant.TenantRepository;
import java.net.URI;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * CLAUDE.md §8: one 200 test and one 403 test per protected endpoint per role. The permissions
 * being asserted come from PRD §26 — only Admin may view the user list ({@code /admin/users}).
 * Creating a user is covered by {@code AdminEmployeeAccessControlTest} instead: {@code
 * AdminUserController} no longer writes anything (see its class javadoc).
 */
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("test")
@SpringBootTest
@AutoConfigureMockMvc
class AdminAccessControlTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private TenantRepository tenants;
    @Autowired private TransactionTemplate transactions;

    private String slug;

    @BeforeEach
    void seedTenant() {
        slug = "rbac" + UUID.randomUUID().toString().substring(0, 8);
        TenantContext.runAsSystem(
                "test: seed tenant",
                () -> transactions.execute(status -> tenants.save(new Tenant(slug, "RBAC Co")).getId()));
    }

    @Test
    void listUsers_asAdmin_returns200() throws Exception {
        mockMvc
                .perform(adminUsers().with(user("admin@rbac.test").roles("ADMIN")))
                .andExpect(status().isOk());
    }

    @Test
    void listUsers_asEmployee_returns403() throws Exception {
        // The sub-phase 1.2 DoD item: /admin/* as an Employee is forbidden.
        mockMvc
                .perform(adminUsers().with(user("employee@rbac.test").roles("EMPLOYEE")))
                .andExpect(status().isForbidden());
    }

    @Test
    void listUsers_asManager_returns403() throws Exception {
        // Guards the direction of the role hierarchy: ADMIN implies MANAGER, never the reverse.
        mockMvc
                .perform(adminUsers().with(user("manager@rbac.test").roles("MANAGER")))
                .andExpect(status().isForbidden());
    }

    @Test
    void listUsers_whenAnonymous_redirectsToLogin() throws Exception {
        mockMvc.perform(adminUsers()).andExpect(status().is3xxRedirection());
    }

    @Test
    void home_asEmployee_returns200() throws Exception {
        mockMvc
                .perform(
                        get(URI.create("http://" + slug + ".localhost/"))
                                .with(user("employee@rbac.test").roles("EMPLOYEE")))
                .andExpect(status().isOk());
    }

    @Test
    void home_asAdmin_returns200() throws Exception {
        // ADMIN reaches an EMPLOYEE-gated page through the role hierarchy, without holding
        // ROLE_EMPLOYEE explicitly.
        mockMvc
                .perform(
                        get(URI.create("http://" + slug + ".localhost/"))
                                .with(user("admin@rbac.test").roles("ADMIN")))
                .andExpect(status().isOk());
    }

    /**
     * The other half of default-deny: the pages a signed-out user must still reach. Getting this
     * wrong locks every user out of their own account with no way back in, and the four pages are
     * one list in {@code SecurityConfig} — so they are asserted as one list here rather than as
     * one test each.
     *
     * <p>The two token-bearing pages are hit with a junk token on purpose: they must render (and
     * say the link is dead), not 403 or blow up.
     */
    @ParameterizedTest
    @ValueSource(
            strings = {
                "/login",
                "/forgot-password",
                "/reset-password?token=not-a-real-token",
                "/accept-invite?token=not-a-real-token"
            })
    void publicAuthPages_whenAnonymous_areReachable(String path) throws Exception {
        mockMvc
                .perform(get(URI.create("http://" + slug + ".localhost" + path)))
                .andExpect(status().isOk());
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder adminUsers() {
        return get(URI.create("http://" + slug + ".localhost/admin/users"));
    }
}
