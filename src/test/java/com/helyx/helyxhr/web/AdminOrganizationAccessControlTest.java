package com.helyx.helyxhr.web;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.helyx.helyxhr.TestcontainersConfiguration;
import com.helyx.helyxhr.org.Division;
import com.helyx.helyxhr.org.DivisionRepository;
import com.helyx.helyxhr.tenant.Tenant;
import com.helyx.helyxhr.tenant.TenantContext;
import com.helyx.helyxhr.tenant.TenantRepository;
import java.net.URI;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * CLAUDE.md §8: one 200 test and one 403 test per protected endpoint per role. Permissions come
 * from PRD §26 — CRUD divisions/departments is Admin-only.
 *
 * <p>Status-only, matching {@code AdminAccessControlTest}: {@code MockMvc}'s test dispatcher
 * doesn't perform the container-level {@code /error} forward that a real servlet container does
 * for a security-filter {@code AccessDeniedException} (unlike a {@code HelyxException} thrown
 * inside a controller method, which {@code GlobalExceptionHandler} renders directly, in-process —
 * no forward involved). Tried asserting the rendered body here; the response came back empty
 * under {@code MockMvc} even though the same request renders {@code error/403.html} in a running
 * app. That gap — {@code ErrorPageResolutionTest} tests view resolution directly rather than
 * through a live 403 — is pre-existing and not specific to this controller, so it's left as-is
 * rather than reworking the test harness's error-page dispatch in this phase.
 */
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("test")
@SpringBootTest
@AutoConfigureMockMvc
class AdminOrganizationAccessControlTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private TenantRepository tenants;
    @Autowired private DivisionRepository divisions;
    @Autowired private TransactionTemplate transactions;

    private String slug;
    private UUID divisionId;

    @BeforeEach
    void seedTenantAndDivision() {
        slug = "org-rbac" + UUID.randomUUID().toString().substring(0, 8);
        UUID tenantId =
                TenantContext.runAsSystem(
                        "test: seed tenant",
                        () -> transactions.execute(status -> tenants.save(new Tenant(slug, "Org RBAC Co")).getId()));

        TenantContext.set(tenantId);
        try {
            divisionId =
                    transactions.execute(
                            status -> divisions.save(Division.create("Engineering", null)).getId());
        } finally {
            TenantContext.clear();
        }
    }

    @Test
    void organizationPage_asAdmin_returns200() throws Exception {
        mockMvc.perform(url("/admin/organization").with(user("admin@org.test").roles("ADMIN")))
                .andExpect(status().isOk());
    }

    @Test
    void organizationPage_asEmployee_returns403() throws Exception {
        mockMvc.perform(url("/admin/organization").with(user("employee@org.test").roles("EMPLOYEE")))
                .andExpect(status().isForbidden());
    }

    @Test
    void organizationPage_asManager_returns403() throws Exception {
        mockMvc.perform(url("/admin/organization").with(user("manager@org.test").roles("MANAGER")))
                .andExpect(status().isForbidden());
    }

    @Test
    void createDivision_asEmployee_returns403() throws Exception {
        mockMvc
                .perform(
                        post(URI.create("http://" + slug + ".localhost/admin/divisions"))
                                .param("name", "Sales")
                                .with(user("employee@org.test").roles("EMPLOYEE"))
                                .with(csrf()))
                .andExpect(status().isForbidden());
    }

    @Test
    void createDepartment_asEmployee_returns403() throws Exception {
        mockMvc
                .perform(
                        post(URI.create("http://" + slug + ".localhost/admin/departments"))
                                .param("name", "Backend")
                                .param("divisionId", divisionId.toString())
                                .with(user("employee@org.test").roles("EMPLOYEE"))
                                .with(csrf()))
                .andExpect(status().isForbidden());
    }

    @Test
    void deleteDivision_asManager_returns403() throws Exception {
        mockMvc
                .perform(
                        delete(URI.create("http://" + slug + ".localhost/admin/divisions/" + divisionId))
                                .with(user("manager@org.test").roles("MANAGER"))
                                .with(csrf()))
                .andExpect(status().isForbidden());
    }

    @Test
    void organizationPage_whenAnonymous_redirectsToLogin() throws Exception {
        mockMvc.perform(url("/admin/organization")).andExpect(status().is3xxRedirection());
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder url(String path) {
        return get(URI.create("http://" + slug + ".localhost" + path));
    }
}
