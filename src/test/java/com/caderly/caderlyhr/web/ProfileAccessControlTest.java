package com.caderly.caderlyhr.web;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.caderly.caderlyhr.identity.AppUserDetailsService;
import com.caderly.caderlyhr.identity.Role;
import com.caderly.caderlyhr.people.Employee;
import com.caderly.caderlyhr.support.RbacTestSupport;
import com.caderly.caderlyhr.tenant.Tenant;
import com.caderly.caderlyhr.tenant.TenantContext;
import java.net.URI;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.userdetails.UserDetails;

/**
 * PRD §26: self, Admin, and a direct/indirect manager may view a profile; anyone else is denied.
 * Uses a real {@code AppUserPrincipal} (via {@link AppUserDetailsService}), not the generic
 * {@code user(String)} post-processor other RBAC tests use — {@code ProfileController} binds
 * {@code @AuthenticationPrincipal AppUserPrincipal}, which only resolves against the real type.
 */
class ProfileAccessControlTest extends RbacTestSupport {

    @BeforeEach
    void seedTenant() {
        slug = "profile-rbac" + UUID.randomUUID().toString().substring(0, 8);
        tenantId =
                TenantContext.runAsSystem(
                        "test: seed tenant", () -> tenants.save(new Tenant(slug, "Profile RBAC Co")).getId());
    }

    @Test
    void viewOwnProfile_returns200() throws Exception {
        Employee self = createEmployee("Self", "Viewer");
        UserDetails principal = loadPrincipal(self.email());

        mockMvc.perform(url("/profile/" + self.requireId()).with(user(principal)))
                .andExpect(status().isOk());
    }

    @Test
    void viewUnrelatedEmployeesProfile_asPlainEmployee_returns403() throws Exception {
        Employee viewer = createEmployee("Viewer", "One");
        Employee target = createEmployee("Target", "One");
        UserDetails principal = loadPrincipal(viewer.email());

        mockMvc.perform(url("/profile/" + target.requireId()).with(user(principal)))
                .andExpect(status().isForbidden());
    }

    @Test
    void viewAnyEmployeesProfile_asAdmin_returns200() throws Exception {
        Employee target = createEmployee("Target", "Two");
        Employee adminEmployee = createEmployee("Admin", "Person");
        grantRole(adminEmployee, Role.ADMIN);
        UserDetails adminPrincipal = loadPrincipal(adminEmployee.email());

        mockMvc.perform(url("/profile/" + target.requireId()).with(user(adminPrincipal)))
                .andExpect(status().isOk());
    }

    @Test
    void viewProfile_whenAnonymous_redirectsToLogin() throws Exception {
        Employee target = createEmployee("Target", "Three");

        mockMvc.perform(url("/profile/" + target.requireId())).andExpect(status().is3xxRedirection());
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder url(String path) {
        return get(URI.create("http://" + slug + ".localhost" + path));
    }
}
