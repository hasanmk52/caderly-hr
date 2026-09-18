package com.caderly.caderlyhr.web;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * CLAUDE.md §8: one 200 and one 403 per protected endpoint per role. PRD §26 puts "Configure
 * tenant settings" — which is what the category switches are — at Admin only, and the delivery log
 * exposes every recipient address in the tenant, so the whole page is Admin-only.
 *
 * <p>These assert status only. CLAUDE.md §6 A05 warns that a status-only assertion passes just as
 * happily when Boot's Whitelabel page is what came back — but MockMvc does not run the servlet
 * error dispatch, so the 403 body is empty here no matter which template would have rendered.
 * {@code ErrorPageResolutionTest} owns that half, at the {@code ErrorViewResolver} level where it
 * can actually be observed. The 200 case below does assert content, because that one really is
 * rendered.
 */
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("test")
@SpringBootTest
@AutoConfigureMockMvc
class AdminNotificationsAccessControlTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private TenantRepository tenants;
    @Autowired private TransactionTemplate transactions;

    private String slug;

    @BeforeEach
    void seedTenant() {
        slug = "notif-rbac" + UUID.randomUUID().toString().substring(0, 8);
        TenantContext.runAsSystem(
                "test: seed tenant",
                () ->
                        transactions.execute(
                                status -> tenants.save(new Tenant(slug, "Notif RBAC Co")).getId()));
    }

    @Test
    void page_asAdmin_returns200() throws Exception {
        mockMvc
                .perform(get(url("/admin/notifications")).with(user("admin@notif.test").roles("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Delivery log")));
    }

    @Test
    void page_asEmployee_returns403() throws Exception {
        mockMvc
                .perform(
                        get(url("/admin/notifications")).with(user("employee@notif.test").roles("EMPLOYEE")))
                .andExpect(status().isForbidden());
    }

    @Test
    void page_asManager_returns403() throws Exception {
        mockMvc
                .perform(get(url("/admin/notifications")).with(user("manager@notif.test").roles("MANAGER")))
                .andExpect(status().isForbidden());
    }

    @Test
    void page_whenAnonymous_redirectsToLogin() throws Exception {
        mockMvc.perform(get(url("/admin/notifications"))).andExpect(status().is3xxRedirection());
    }

    @Test
    void saveSettings_asAdmin_returns200() throws Exception {
        mockMvc
                .perform(
                        settingsPost()
                                .param("holidayReminder", "true")
                                .with(user("admin@notif.test").roles("ADMIN")))
                .andExpect(status().isOk());
    }

    @Test
    void saveSettings_asManager_returns403() throws Exception {
        mockMvc
                .perform(
                        settingsPost()
                                .param("holidayReminder", "true")
                                .with(user("manager@notif.test").roles("MANAGER")))
                .andExpect(status().isForbidden());
    }

    @Test
    void saveSettings_asEmployee_returns403() throws Exception {
        mockMvc
                .perform(
                        settingsPost()
                                .param("holidayReminder", "true")
                                .with(user("employee@notif.test").roles("EMPLOYEE")))
                .andExpect(status().isForbidden());
    }

    @Test
    void retry_asEmployee_returns403() throws Exception {
        mockMvc.perform(retryPost().with(user("employee@notif.test").roles("EMPLOYEE")))
                .andExpect(status().isForbidden());
    }

    @Test
    void retry_asManager_returns403() throws Exception {
        mockMvc.perform(retryPost().with(user("manager@notif.test").roles("MANAGER")))
                .andExpect(status().isForbidden());
    }

    @Test
    void retry_asAdmin_onAnUnknownId_returns404NotForbidden() throws Exception {
        // The Admin passes authorization and is then told the row does not exist, which is also
        // what another tenant's row looks like from here (NotificationAdminServiceTest proves it).
        mockMvc.perform(retryPost().with(user("admin@notif.test").roles("ADMIN")))
                .andExpect(status().isNotFound());
    }

    private MockHttpServletRequestBuilder settingsPost() {
        return post(url("/admin/notifications/settings")).with(csrf());
    }

    private MockHttpServletRequestBuilder retryPost() {
        return post(url("/admin/notifications/" + UUID.randomUUID() + "/retry")).with(csrf());
    }

    private URI url(String path) {
        return URI.create("http://" + slug + ".localhost" + path);
    }
}
