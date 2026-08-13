package com.helyx.helyxhr.web;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.helyx.helyxhr.TestcontainersConfiguration;
import com.helyx.helyxhr.identity.AppUser;
import com.helyx.helyxhr.identity.AppUserDetailsService;
import com.helyx.helyxhr.identity.AppUserRepository;
import com.helyx.helyxhr.identity.Role;
import com.helyx.helyxhr.people.Employee;
import com.helyx.helyxhr.people.EmployeeForms;
import com.helyx.helyxhr.people.EmployeeService;
import com.helyx.helyxhr.people.GovernmentIdType;
import com.helyx.helyxhr.tenant.Tenant;
import com.helyx.helyxhr.tenant.TenantContext;
import com.helyx.helyxhr.tenant.TenantRepository;
import java.net.URI;
import java.util.UUID;
import org.hamcrest.Matchers;
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
 * Regression coverage: an added Government ID's {@code idNumber} — encrypted at rest via {@link
 * com.helyx.helyxhr.common.CryptoConverter} — must actually render back on the owner's own
 * profile. {@code people/profile.html}'s Government IDs list used to print only {@code
 * idType.label}, never the number itself, even though {@link
 * com.helyx.helyxhr.people.GovernmentId}'s class doc always said the field is "visible ... to ...
 * the owning employee."
 */
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("test")
@SpringBootTest
@AutoConfigureMockMvc
class ProfileGovernmentIdTest {

    private static final String BASE_URL = "https://acme.localhost";
    private static final String TENANT_NAME = "Acme";

    @Autowired private MockMvc mockMvc;
    @Autowired private TenantRepository tenants;
    @Autowired private EmployeeService employeeService;
    @Autowired private AppUserDetailsService userDetailsService;
    @Autowired private AppUserRepository appUsers;

    private String slug;
    private UUID tenantId;

    @BeforeEach
    void seedTenant() {
        slug = "gov-id" + UUID.randomUUID().toString().substring(0, 8);
        tenantId =
                TenantContext.runAsSystem(
                        "test: seed tenant", () -> tenants.save(new Tenant(slug, "Gov Id Co")).getId());
    }

    @Test
    void addGovernmentId_thenOwnProfileRendersTheIdNumber() throws Exception {
        Employee self = createEmployee("Jamie", "Lee");
        UserDetails principal = loadPrincipal(self.email());

        mockMvc
                .perform(
                        post(URI.create("http://" + slug + ".localhost/profile/government-ids"))
                                .param("idType", "PASSPORT")
                                .param("idNumber", "P9876543")
                                .with(user(principal))
                                .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(content().string(Matchers.containsString("P9876543")));
    }

    /**
     * PRD §26: Admin gets full read access to any profile, but may not add/remove another
     * employee's Government ID entries — only the owning employee can. The read-only view must
     * show the number without the delete button or the add form.
     */
    @Test
    void adminViewingAnotherEmployeesProfile_seesIdNumberButNoEditControls() throws Exception {
        Employee target = createEmployee("Target", "Person");
        run(
                () ->
                        employeeService.addGovernmentId(
                                target.requireId(), GovernmentIdType.PASSPORT, "P1112223", null, null, null));
        Employee adminEmployee = createEmployee("Admin", "Person");
        grantRole(adminEmployee, Role.ADMIN);
        UserDetails adminPrincipal = loadPrincipal(adminEmployee.email());

        mockMvc
                .perform(
                        get(URI.create("http://" + slug + ".localhost/profile/" + target.requireId()))
                                .with(user(adminPrincipal)))
                .andExpect(status().isOk())
                .andExpect(content().string(Matchers.containsString("P1112223")))
                .andExpect(content().string(Matchers.not(Matchers.containsString("Remove ID"))))
                .andExpect(content().string(Matchers.not(Matchers.containsString("ID number"))));
    }

    private void grantRole(Employee employee, Role role) {
        run(
                () -> {
                    AppUser user = appUsers.findById(employee.userId()).orElseThrow();
                    user.grant(role);
                    return appUsers.save(user);
                });
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
}
