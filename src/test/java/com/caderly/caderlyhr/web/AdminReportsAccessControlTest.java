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
 * CLAUDE.md §8: one 200 and one 403 per protected endpoint per role. PRD §26 puts "Generate
 * reports" at Admin only — every page and every CSV download in this controller is Admin-only.
 *
 * <p>Status-only, same caveat as {@code AdminNotificationsAccessControlTest}: MockMvc does not run
 * the servlet error dispatch, so a 403 here has an empty body regardless of which template would
 * have rendered. {@code ErrorPageResolutionTest} owns proving the rendered page at the {@code
 * ErrorViewResolver} level.
 */
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("test")
@SpringBootTest
@AutoConfigureMockMvc
class AdminReportsAccessControlTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private TenantRepository tenants;
    @Autowired private TransactionTemplate transactions;

    private String slug;

    @BeforeEach
    void seedTenant() {
        slug = "reports-rbac" + UUID.randomUUID().toString().substring(0, 8);
        TenantContext.runAsSystem(
                "test: seed tenant",
                () -> transactions.execute(status -> tenants.save(new Tenant(slug, "Reports RBAC Co")).getId()));
    }

    @Test
    void landing_asAdmin_returns200() throws Exception {
        mockMvc
                .perform(get(url("/admin/reports")).with(user("admin@reports.test").roles("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Reports")));
    }

    @Test
    void landing_asEmployee_returns403() throws Exception {
        mockMvc
                .perform(get(url("/admin/reports")).with(user("employee@reports.test").roles("EMPLOYEE")))
                .andExpect(status().isForbidden());
    }

    @Test
    void landing_asManager_returns403() throws Exception {
        mockMvc
                .perform(get(url("/admin/reports")).with(user("manager@reports.test").roles("MANAGER")))
                .andExpect(status().isForbidden());
    }

    @Test
    void landing_whenAnonymous_redirectsToLogin() throws Exception {
        mockMvc.perform(get(url("/admin/reports"))).andExpect(status().is3xxRedirection());
    }

    @Test
    void leaveBalancePage_asAdmin_returns200() throws Exception {
        mockMvc
                .perform(get(url("/admin/reports/leave-balance")).with(user("admin@reports.test").roles("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Leave Balance")));
    }

    @Test
    void leaveBalancePage_asEmployee_returns403() throws Exception {
        mockMvc
                .perform(
                        get(url("/admin/reports/leave-balance")).with(user("employee@reports.test").roles("EMPLOYEE")))
                .andExpect(status().isForbidden());
    }

    @Test
    void leaveBalanceCsv_asAdmin_returns200WithCsvContentType() throws Exception {
        mockMvc
                .perform(
                        get(url("/admin/reports/leave-balance.csv")).with(user("admin@reports.test").roles("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("text/csv"));
    }

    @Test
    void leaveBalanceCsv_asEmployee_returns403() throws Exception {
        mockMvc
                .perform(
                        get(url("/admin/reports/leave-balance.csv")).with(user("employee@reports.test").roles("EMPLOYEE")))
                .andExpect(status().isForbidden());
    }

    @Test
    void leaveBalanceCsv_asManager_returns403() throws Exception {
        mockMvc
                .perform(
                        get(url("/admin/reports/leave-balance.csv")).with(user("manager@reports.test").roles("MANAGER")))
                .andExpect(status().isForbidden());
    }

    @Test
    void leaveUtilizationPage_asAdmin_returns200() throws Exception {
        mockMvc
                .perform(
                        get(url("/admin/reports/leave-utilization")).with(user("admin@reports.test").roles("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Leave Utilization")));
    }

    @Test
    void leaveUtilizationPage_asEmployee_returns403() throws Exception {
        mockMvc
                .perform(
                        get(url("/admin/reports/leave-utilization"))
                                .with(user("employee@reports.test").roles("EMPLOYEE")))
                .andExpect(status().isForbidden());
    }

    @Test
    void leaveUtilizationCsv_asAdmin_returns200WithCsvContentType() throws Exception {
        mockMvc
                .perform(
                        get(url("/admin/reports/leave-utilization.csv"))
                                .with(user("admin@reports.test").roles("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("text/csv"));
    }

    @Test
    void leaveUtilizationCsv_asManager_returns403() throws Exception {
        mockMvc
                .perform(
                        get(url("/admin/reports/leave-utilization.csv"))
                                .with(user("manager@reports.test").roles("MANAGER")))
                .andExpect(status().isForbidden());
    }

    @Test
    void headcountPage_asAdmin_returns200() throws Exception {
        mockMvc
                .perform(get(url("/admin/reports/headcount")).with(user("admin@reports.test").roles("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Headcount")));
    }

    @Test
    void headcountPage_asEmployee_returns403() throws Exception {
        mockMvc
                .perform(get(url("/admin/reports/headcount")).with(user("employee@reports.test").roles("EMPLOYEE")))
                .andExpect(status().isForbidden());
    }

    @Test
    void headcountCsv_asAdmin_returns200WithCsvContentType() throws Exception {
        mockMvc
                .perform(get(url("/admin/reports/headcount.csv")).with(user("admin@reports.test").roles("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("text/csv"));
    }

    @Test
    void headcountCsv_asManager_returns403() throws Exception {
        mockMvc
                .perform(get(url("/admin/reports/headcount.csv")).with(user("manager@reports.test").roles("MANAGER")))
                .andExpect(status().isForbidden());
    }

    private URI url(String path) {
        return URI.create("http://" + slug + ".localhost" + path);
    }
}
