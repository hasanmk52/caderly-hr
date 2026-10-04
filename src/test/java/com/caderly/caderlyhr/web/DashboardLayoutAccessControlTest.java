package com.caderly.caderlyhr.web;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.caderly.caderlyhr.identity.AppUser;
import com.caderly.caderlyhr.identity.Role;
import com.caderly.caderlyhr.support.RbacTestSupport;
import java.net.URI;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * CLAUDE.md §8: 200 per role plus anonymous-denied for both dashboard-layout endpoints, using a
 * real {@code AppUserPrincipal} (the controller binds it). Mirrors {@link HomeWidgetAccessControlTest}.
 */
class DashboardLayoutAccessControlTest extends RbacTestSupport {

    @BeforeEach
    void seedTenant() {
        slug = "layout-rbac" + UUID.randomUUID().toString().substring(0, 8);
        tenantId = com.caderly.caderlyhr.tenant.TenantContext.runAsSystem(
                "test: seed tenant",
                () -> tenants.save(new com.caderly.caderlyhr.tenant.Tenant(slug, "Layout RBAC Co")).getId());
    }

    @Test
    void save_asEachRole_returns200WithHxRedirect() throws Exception {
        for (Role role : new Role[] {Role.EMPLOYEE, Role.MANAGER, Role.ADMIN}) {
            UserDetails principal = createUser(role.name().toLowerCase() + "@layout.test", role);
            mockMvc.perform(save("resources,my-peers", "my-peers").with(user(principal)).with(csrf()))
                    .andExpect(status().isOk())
                    .andExpect(header().string("HX-Redirect", "/"));
        }
    }

    @Test
    void reset_asEachRole_returns200WithHxRedirect() throws Exception {
        for (Role role : new Role[] {Role.EMPLOYEE, Role.MANAGER, Role.ADMIN}) {
            UserDetails principal = createUser("r-" + role.name().toLowerCase() + "@layout.test", role);
            mockMvc.perform(post(url("/dashboard/layout/reset")).with(user(principal)).with(csrf()))
                    .andExpect(status().isOk())
                    .andExpect(header().string("HX-Redirect", "/"));
        }
    }

    @Test
    void save_whenAnonymous_redirectsToLogin() throws Exception {
        mockMvc.perform(save("resources", "").with(csrf())).andExpect(status().is3xxRedirection());
    }

    @Test
    void reset_whenAnonymous_redirectsToLogin() throws Exception {
        mockMvc.perform(post(url("/dashboard/layout/reset")).with(csrf()))
                .andExpect(status().is3xxRedirection());
    }

    @Test
    void save_withoutCsrfToken_returns403() throws Exception {
        UserDetails principal = createUser("nocsrf@layout.test", Role.EMPLOYEE);
        mockMvc.perform(save("resources", "").with(user(principal))).andExpect(status().isForbidden());
    }

    private MockHttpServletRequestBuilder save(String order, String hidden) {
        return post(url("/dashboard/layout")).param("order", order.split(",")).param("hidden", hidden.split(","));
    }

    private UserDetails createUser(String email, Role role) {
        run(
                () -> {
                    AppUser user = AppUser.active(email, "{noop}unused");
                    user.grant(role);
                    return appUsers.save(user);
                });
        return run(() -> userDetailsService.loadUserByUsername(email));
    }

    private URI url(String path) {
        return URI.create("http://" + slug + ".localhost" + path);
    }
}
