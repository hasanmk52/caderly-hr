package com.caderly.caderlyhr.web;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.caderly.caderlyhr.TestcontainersConfiguration;
import com.caderly.caderlyhr.tenant.Tenant;
import com.caderly.caderlyhr.tenant.TenantContext;
import com.caderly.caderlyhr.tenant.TenantRepository;
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
 * CLAUDE.md §8: one 200 and one 403 per protected endpoint per role. PRD §26 puts "View audit log"
 * at Admin only (Super Admin's cross-tenant view is Phase 1.13's job, once that console exists).
 * Shape copied from {@code AdminNotificationsAccessControlTest}.
 */
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("test")
@SpringBootTest
@AutoConfigureMockMvc
class AdminAuditLogAccessControlTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private TenantRepository tenants;
    @Autowired private TransactionTemplate transactions;

    private String slug;

    @BeforeEach
    void seedTenant() {
        slug = "audit-rbac" + UUID.randomUUID().toString().substring(0, 8);
        TenantContext.runAsSystem(
                "test: seed tenant",
                () -> transactions.execute(status -> tenants.save(new Tenant(slug, "Audit RBAC Co")).getId()));
    }

    @Test
    void writesTab_asAdmin_returns200() throws Exception {
        mockMvc
                .perform(get(url("/admin/audit-log")).with(user("admin@audit.test").roles("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Audit Log")));
    }

    @Test
    void loginsTab_asAdmin_returns200() throws Exception {
        mockMvc
                .perform(
                        get(url("/admin/audit-log")).param("tab", "logins")
                                .with(user("admin@audit.test").roles("ADMIN")))
                .andExpect(status().isOk());
    }

    @Test
    void page_asEmployee_returns403() throws Exception {
        mockMvc
                .perform(get(url("/admin/audit-log")).with(user("employee@audit.test").roles("EMPLOYEE")))
                .andExpect(status().isForbidden());
    }

    @Test
    void page_asManager_returns403() throws Exception {
        mockMvc
                .perform(get(url("/admin/audit-log")).with(user("manager@audit.test").roles("MANAGER")))
                .andExpect(status().isForbidden());
    }

    @Test
    void page_whenAnonymous_redirectsToLogin() throws Exception {
        mockMvc.perform(get(url("/admin/audit-log"))).andExpect(status().is3xxRedirection());
    }

    @Test
    void diff_asAdmin_onAnUnknownId_returns404NotForbidden() throws Exception {
        mockMvc
                .perform(
                        get(url("/admin/audit-log/" + UUID.randomUUID() + "/diff"))
                                .with(user("admin@audit.test").roles("ADMIN")))
                .andExpect(status().isNotFound());
    }

    @Test
    void diff_asEmployee_returns403() throws Exception {
        mockMvc
                .perform(
                        get(url("/admin/audit-log/" + UUID.randomUUID() + "/diff"))
                                .with(user("employee@audit.test").roles("EMPLOYEE")))
                .andExpect(status().isForbidden());
    }

    private URI url(String path) {
        return URI.create("http://" + slug + ".localhost" + path);
    }
}
