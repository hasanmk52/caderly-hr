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
 * PRD §26: self, Admin, and a direct/indirect manager may view a profile; anyone else is denied.
 * Uses a real {@code AppUserPrincipal} (via {@link AppUserDetailsService}), not the generic
 * {@code user(String)} post-processor other RBAC tests use — {@code ProfileController} binds
 * {@code @AuthenticationPrincipal AppUserPrincipal}, which only resolves against the real type.
 */
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("test")
@SpringBootTest
@AutoConfigureMockMvc
class ProfileAccessControlTest {

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

    private <T> T run(java.util.function.Supplier<T> action) {
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
