package com.helyx.helyxhr.web;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.helyx.helyxhr.TestcontainersConfiguration;
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
 * CLAUDE.md §8: one 200 and one 403 test per protected endpoint per role. Permissions come from
 * PRD §26 — Employee CRUD is Admin-only. Mirrors {@code AdminOrganizationAccessControlTest}'s
 * status-only scope (see its Javadoc for why the rendered-403-body assertion doesn't work under
 * {@code MockMvc} for a security-filter {@code AccessDeniedException}).
 */
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("test")
@SpringBootTest
@AutoConfigureMockMvc
class AdminEmployeeAccessControlTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private TenantRepository tenants;
    @Autowired private TransactionTemplate transactions;

    private String slug;

    @BeforeEach
    void seedTenant() {
        slug = "emp-rbac" + UUID.randomUUID().toString().substring(0, 8);
        TenantContext.runAsSystem(
                "test: seed tenant",
                () -> transactions.execute(status -> tenants.save(new Tenant(slug, "Emp RBAC Co")).getId()));
    }

    @Test
    void employeeList_asAdmin_returns200() throws Exception {
        mockMvc.perform(url("/admin/employees").with(user("admin@emp.test").roles("ADMIN")))
                .andExpect(status().isOk());
    }

    @Test
    void employeeList_asEmployee_returns403() throws Exception {
        mockMvc.perform(url("/admin/employees").with(user("employee@emp.test").roles("EMPLOYEE")))
                .andExpect(status().isForbidden());
    }

    @Test
    void employeeList_asManager_returns403() throws Exception {
        mockMvc.perform(url("/admin/employees").with(user("manager@emp.test").roles("MANAGER")))
                .andExpect(status().isForbidden());
    }

    @Test
    void createEmployee_asEmployee_returns403() throws Exception {
        mockMvc
                .perform(
                        post(URI.create("http://" + slug + ".localhost/admin/employees"))
                                .param("firstName", "Jane")
                                .param("lastName", "Doe")
                                .param("email", "jane@emp.test")
                                .with(user("employee@emp.test").roles("EMPLOYEE"))
                                .with(csrf()))
                .andExpect(status().isForbidden());
    }

    @Test
    void employeeList_whenAnonymous_redirectsToLogin() throws Exception {
        mockMvc.perform(url("/admin/employees")).andExpect(status().is3xxRedirection());
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder url(String path) {
        return get(URI.create("http://" + slug + ".localhost" + path));
    }
}
