package com.caderly.caderlyhr.web;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.caderly.caderlyhr.identity.AppUser;
import com.caderly.caderlyhr.support.RbacTestSupport;
import com.caderly.caderlyhr.tenant.Tenant;
import com.caderly.caderlyhr.tenant.TenantContext;
import java.net.URI;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * PRD §26: "View team calendar" is ✅ for Employee, Manager, and Admin alike — {@code
 * isAuthenticated()} is the whole access check, no role gate on top (CalendarController's
 * Javadoc). CLAUDE.md §8: one 200 test per role plus one anonymous-denied test.
 */
class CalendarControllerAccessControlTest extends RbacTestSupport {

    @BeforeEach
    void seedTenant() {
        slug = "cal-rbac" + UUID.randomUUID().toString().substring(0, 8);
        tenantId =
                TenantContext.runAsSystem(
                        "test: seed tenant", () -> tenants.save(new Tenant(slug, "Calendar RBAC Co")).getId());
    }

    @Test
    void calendarPage_asEmployee_returns200() throws Exception {
        mockMvc.perform(url("/calendar").with(user("employee@cal.test").roles("EMPLOYEE")))
                .andExpect(status().isOk());
    }

    @Test
    void calendarPage_asManager_returns200() throws Exception {
        mockMvc.perform(url("/calendar").with(user("manager@cal.test").roles("MANAGER")))
                .andExpect(status().isOk());
    }

    @Test
    void calendarPage_asAdmin_returns200() throws Exception {
        mockMvc.perform(url("/calendar").with(user("admin@cal.test").roles("ADMIN")))
                .andExpect(status().isOk());
    }

    @Test
    void calendarPage_whenAnonymous_redirectsToLogin() throws Exception {
        mockMvc.perform(url("/calendar")).andExpect(status().is3xxRedirection());
    }

    @Test
    void settingsCalendarPage_asEmployee_returns200() throws Exception {
        UserDetails principal = loadPrincipal(createUser());

        mockMvc.perform(url("/settings/calendar").with(user(principal))).andExpect(status().isOk());
    }

    @Test
    void settingsCalendarPage_whenAnonymous_redirectsToLogin() throws Exception {
        mockMvc.perform(url("/settings/calendar")).andExpect(status().is3xxRedirection());
    }

    @Test
    void regenerate_whenAnonymous_redirectsToLogin() throws Exception {
        mockMvc
                .perform(postUrl("/settings/calendar/regenerate").with(csrf()))
                .andExpect(status().is3xxRedirection());
    }

    private String createUser() {
        String email = "settings-" + UUID.randomUUID() + "@cal.test";
        run(() -> appUsers.save(AppUser.active(email, "hash")));
        return email;
    }

    private MockHttpServletRequestBuilder url(String path) {
        return get(URI.create("http://" + slug + ".localhost" + path));
    }

    private MockHttpServletRequestBuilder postUrl(String path) {
        return post(URI.create("http://" + slug + ".localhost" + path));
    }
}
