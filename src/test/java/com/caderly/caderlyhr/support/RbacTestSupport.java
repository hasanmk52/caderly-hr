package com.caderly.caderlyhr.support;

import com.caderly.caderlyhr.TestcontainersConfiguration;
import com.caderly.caderlyhr.identity.AppUser;
import com.caderly.caderlyhr.identity.AppUserDetailsService;
import com.caderly.caderlyhr.identity.AppUserRepository;
import com.caderly.caderlyhr.identity.Role;
import com.caderly.caderlyhr.people.Employee;
import com.caderly.caderlyhr.people.EmployeeForms;
import com.caderly.caderlyhr.people.EmployeeService;
import com.caderly.caderlyhr.tenant.TenantContext;
import com.caderly.caderlyhr.tenant.TenantRepository;
import java.time.LocalDate;
import java.util.UUID;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Shared fixture for {@code web/*AccessControlTest} classes: every subclass needs its own tenant,
 * an {@link Employee} to act as, a role grant, and a way to run tenant-scoped setup code before
 * the request under test. Subclasses still own their own {@code @BeforeEach seedTenant()} (slug
 * prefix and tenant display name are deliberately per-file, not templated here).
 */
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("test")
@SpringBootTest
@AutoConfigureMockMvc
public abstract class RbacTestSupport {

    protected static final String BASE_URL = "https://acme.localhost";
    protected static final String TENANT_NAME = "Acme";

    @Autowired protected MockMvc mockMvc;
    @Autowired protected TenantRepository tenants;
    @Autowired protected EmployeeService employeeService;
    @Autowired protected AppUserRepository appUsers;
    @Autowired protected AppUserDetailsService userDetailsService;

    protected String slug;
    protected UUID tenantId;

    protected <T> T run(Supplier<T> action) {
        TenantContext.set(tenantId);
        try {
            return action.get();
        } finally {
            TenantContext.clear();
        }
    }

    protected void run(Runnable action) {
        TenantContext.set(tenantId);
        try {
            action.run();
        } finally {
            TenantContext.clear();
        }
    }

    protected UserDetails loadPrincipal(String email) {
        return run(() -> userDetailsService.loadUserByUsername(email));
    }

    protected Employee createEmployee(String firstName, String lastName) {
        return createEmployee(firstName, lastName, null, null, null);
    }

    protected Employee createEmployee(
            String firstName,
            String lastName,
            @Nullable LocalDate hireDate,
            @Nullable UUID departmentId,
            @Nullable UUID managerId) {
        Employee employee =
                run(
                        () ->
                                employeeService.create(
                                        new EmployeeForms.CreateEmployee(
                                                firstName,
                                                lastName,
                                                UUID.randomUUID() + "@example.test",
                                                null,
                                                null,
                                                null,
                                                null,
                                                null,
                                                null,
                                                null,
                                                hireDate,
                                                null,
                                                departmentId,
                                                managerId,
                                                null,
                                                null,
                                                null,
                                                null,
                                                null),
                                        BASE_URL,
                                        TENANT_NAME));
        run(() -> employeeService.activateForUser(employee.userId()));
        return run(() -> employeeService.require(employee.requireId()));
    }

    protected void grantRole(Employee employee, Role role) {
        run(
                () -> {
                    AppUser user = appUsers.findById(employee.userId()).orElseThrow();
                    user.grant(role);
                    return appUsers.save(user);
                });
    }
}
