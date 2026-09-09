package com.caderly.caderlyhr.web;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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
 * CLAUDE.md §8: one 200 test per role plus one anonymous-redirect test. {@code hasRole('EMPLOYEE')}
 * is the floor for self-service booking (PRD §26) — the role hierarchy passes Manager/Admin
 * through too, so all three signed-in roles reach 200. Mirrors {@code ProfileAccessControlTest}'s
 * real-{@code AppUserPrincipal} fixture pattern — {@code LeaveRequestController} resolves the
 * caller's own {@code Employee} via {@code @AuthenticationPrincipal AppUserPrincipal}.
 */
class LeaveRequestAccessControlTest extends RbacTestSupport {

    @BeforeEach
    void seedTenant() {
        slug = "leave-req-rbac" + UUID.randomUUID().toString().substring(0, 8);
        tenantId =
                TenantContext.runAsSystem(
                        "test: seed tenant", () -> tenants.save(new Tenant(slug, "Leave Req RBAC Co")).getId());
    }

    @Test
    void newLeaveForm_asEmployee_returns200() throws Exception {
        Employee employee = createEmployee("Rank", "File");
        UserDetails principal = loadPrincipal(employee.email());

        mockMvc.perform(url("/leave/new").with(user(principal))).andExpect(status().isOk());
    }

    @Test
    void newLeaveForm_asManager_returns200() throws Exception {
        Employee employee = createEmployee("Middle", "Manager");
        grantRole(employee, Role.MANAGER);
        UserDetails principal = loadPrincipal(employee.email());

        mockMvc.perform(url("/leave/new").with(user(principal))).andExpect(status().isOk());
    }

    @Test
    void newLeaveForm_asAdmin_returns200() throws Exception {
        Employee employee = createEmployee("Top", "Admin");
        grantRole(employee, Role.ADMIN);
        UserDetails principal = loadPrincipal(employee.email());

        mockMvc.perform(url("/leave/new").with(user(principal))).andExpect(status().isOk());
    }

    @Test
    void newLeaveForm_whenAnonymous_redirectsToLogin() throws Exception {
        mockMvc.perform(url("/leave/new")).andExpect(status().is3xxRedirection());
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder url(String path) {
        return get(URI.create("http://" + slug + ".localhost" + path));
    }
}
