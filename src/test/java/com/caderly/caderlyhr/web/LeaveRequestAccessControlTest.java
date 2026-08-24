package com.caderly.caderlyhr.web;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.caderly.caderlyhr.TestcontainersConfiguration;
import com.caderly.caderlyhr.identity.AppUser;
import com.caderly.caderlyhr.identity.AppUserDetailsService;
import com.caderly.caderlyhr.identity.AppUserRepository;
import com.caderly.caderlyhr.identity.Role;
import com.caderly.caderlyhr.people.Employee;
import com.caderly.caderlyhr.people.EmployeeForms;
import com.caderly.caderlyhr.people.EmployeeService;
import com.caderly.caderlyhr.tenant.Tenant;
import com.caderly.caderlyhr.tenant.TenantContext;
import com.caderly.caderlyhr.tenant.TenantRepository;
import java.net.URI;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

/**
 * CLAUDE.md §8: one 200 test per role plus one anonymous-redirect test. {@code hasRole('EMPLOYEE')}
 * is the floor for self-service booking (PRD §26) — the role hierarchy passes Manager/Admin
 * through too, so all three signed-in roles reach 200. Mirrors {@code ProfileAccessControlTest}'s
 * real-{@code AppUserPrincipal} fixture pattern — {@code LeaveRequestController} resolves the
 * caller's own {@code Employee} via {@code @AuthenticationPrincipal AppUserPrincipal}.
 */
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("test")
@SpringBootTest
@AutoConfigureMockMvc
class LeaveRequestAccessControlTest {

    private static final String BASE_URL = "https://acme.localhost";
    private static final String TENANT_NAME = "Acme";

    @Autowired private MockMvc mockMvc;
    @Autowired private TenantRepository tenants;
    @Autowired private EmployeeService employeeService;
    @Autowired private AppUserRepository appUsers;
    @Autowired private AppUserDetailsService userDetailsService;

    private String slug;
    private UUID tenantId;

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

    private Employee createEmployee(String firstName, String lastName) {
        Employee employee =
                run(
                        () ->
                                employeeService.create(
                                        new EmployeeForms.CreateEmployee(
                                                firstName,
                                                lastName,
                                                UUID.randomUUID() + "@example.test",
                                                null, null, null, null, null, null, null, null, null, null, null,
                                                null, null, null, null, null),
                                        BASE_URL,
                                        TENANT_NAME));
        run(() -> employeeService.activateForUser(employee.userId()));
        return run(() -> employeeService.require(employee.requireId()));
    }

    private void grantRole(Employee employee, Role role) {
        run(
                () -> {
                    AppUser user = appUsers.findById(employee.userId()).orElseThrow();
                    user.grant(role);
                    return appUsers.save(user);
                });
    }

    private UserDetails loadPrincipal(String email) {
        return run(() -> userDetailsService.loadUserByUsername(email));
    }

    private <T> T run(Supplier<T> action) {
        TenantContext.set(tenantId);
        try {
            return action.get();
        } finally {
            TenantContext.clear();
        }
    }

    private void run(Runnable action) {
        TenantContext.set(tenantId);
        try {
            action.run();
        } finally {
            TenantContext.clear();
        }
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder url(String path) {
        return get(URI.create("http://" + slug + ".localhost" + path));
    }
}
