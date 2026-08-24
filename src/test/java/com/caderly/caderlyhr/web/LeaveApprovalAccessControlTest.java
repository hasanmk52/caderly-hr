package com.caderly.caderlyhr.web;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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
import com.caderly.caderlyhr.timeoff.LeaveRequest;
import com.caderly.caderlyhr.timeoff.LeaveRequestService;
import com.caderly.caderlyhr.timeoff.LeaveType;
import com.caderly.caderlyhr.timeoff.LeaveTypeService;
import java.math.BigDecimal;
import java.net.URI;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.temporal.TemporalAdjusters;
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
 * CLAUDE.md §8: one 200/403 test per role for the For Action page, plus the specific authority
 * checks {@code LeaveRequestService#requireApprovalAuthority} enforces (PRD §26, BR-6). The
 * unrelated-manager case is the actual RBAC gap this phase closes — before booking/approval
 * existed there was no "manager of a specific employee" boundary to test at all.
 */
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("test")
@SpringBootTest
@AutoConfigureMockMvc
class LeaveApprovalAccessControlTest {

    private static final String BASE_URL = "https://acme.localhost";
    private static final String TENANT_NAME = "Acme";

    @Autowired private MockMvc mockMvc;
    @Autowired private TenantRepository tenants;
    @Autowired private EmployeeService employeeService;
    @Autowired private AppUserRepository appUsers;
    @Autowired private AppUserDetailsService userDetailsService;
    @Autowired private LeaveTypeService leaveTypeService;
    @Autowired private LeaveRequestService leaveRequestService;

    private String slug;
    private UUID tenantId;

    @BeforeEach
    void seedTenant() {
        slug = "leave-appr-rbac" + UUID.randomUUID().toString().substring(0, 8);
        tenantId =
                TenantContext.runAsSystem(
                        "test: seed tenant", () -> tenants.save(new Tenant(slug, "Leave Appr RBAC Co")).getId());
        // Must exist before any employee is created below: BalanceService.grantOnHire (fired by
        // EmployeeHiredEvent) only grants against leave types active at hire time.
        leaveTypeCache =
                run(
                        () ->
                                leaveTypeService.create(
                                        "Annual", null, null, true, true, false, true, new BigDecimal("30"), null));
    }

    @Test
    void forAction_asManager_returns200() throws Exception {
        Employee manager = createEmployee("Mgr", "One", null);
        grantRole(manager, Role.MANAGER);
        UserDetails principal = loadPrincipal(manager.email());

        mockMvc.perform(url("/for-action").with(user(principal))).andExpect(status().isOk());
    }

    @Test
    void forAction_asAdmin_returns200() throws Exception {
        Employee admin = createEmployee("Top", "Admin", null);
        grantRole(admin, Role.ADMIN);
        UserDetails principal = loadPrincipal(admin.email());

        mockMvc.perform(url("/for-action").with(user(principal))).andExpect(status().isOk());
    }

    @Test
    void forAction_asEmployee_returns403() throws Exception {
        Employee employee = createEmployee("Rank", "File", null);
        UserDetails principal = loadPrincipal(employee.email());

        mockMvc.perform(url("/for-action").with(user(principal))).andExpect(status().isForbidden());
    }

    @Test
    void forAction_whenAnonymous_redirectsToLogin() throws Exception {
        mockMvc.perform(url("/for-action")).andExpect(status().is3xxRedirection());
    }

    @Test
    void forAction_asManagerWithRealReports_returns200_withoutManuallyGrantingTheRole() throws Exception {
        // Proves the original bug (ADR 0011) is fixed: a real manager, set via manager_id, no
        // longer needs a manual grantRole(...) to pass hasRole('MANAGER') on /for-action. Unlike
        // every other test in this class, there is deliberately no grantRole call here.
        Employee manager = createEmployee("Real", "Manager", null);
        createEmployee("Direct", "Report", manager.requireId());
        UserDetails principal = loadPrincipal(manager.email());

        mockMvc.perform(url("/for-action").with(user(principal))).andExpect(status().isOk());
    }

    @Test
    void approve_asReportsManager_returns200() throws Exception {
        Employee manager = createEmployee("Direct", "Manager", null);
        grantRole(manager, Role.MANAGER);
        Employee report = createEmployee("Direct", "Report", manager.requireId());
        LeaveRequest request = bookLeaveRequest(report);
        UserDetails principal = loadPrincipal(manager.email());

        mockMvc
                .perform(
                        post(URI.create("http://" + slug + ".localhost/for-action/leave-requests/" + request.requireId() + "/approve"))
                                .with(user(principal))
                                .with(csrf()))
                .andExpect(status().isOk());
    }

    @Test
    void approve_asUnrelatedManager_returns403() throws Exception {
        // The specific gap this phase closes: being *a* manager is not enough — you must manage
        // *this* employee, directly or transitively (PRD §26).
        Employee actualManager = createEmployee("Direct", "Manager", null);
        Employee report = createEmployee("Direct", "Report", actualManager.requireId());
        LeaveRequest request = bookLeaveRequest(report);

        Employee unrelatedManager = createEmployee("Unrelated", "Manager", null);
        grantRole(unrelatedManager, Role.MANAGER);
        UserDetails principal = loadPrincipal(unrelatedManager.email());

        mockMvc
                .perform(
                        post(URI.create("http://" + slug + ".localhost/for-action/leave-requests/" + request.requireId() + "/approve"))
                                .with(user(principal))
                                .with(csrf()))
                .andExpect(status().isForbidden());
    }

    @Test
    void approve_asEmployee_returns403() throws Exception {
        Employee manager = createEmployee("Direct", "Manager", null);
        Employee report = createEmployee("Direct", "Report", manager.requireId());
        LeaveRequest request = bookLeaveRequest(report);
        UserDetails principal = loadPrincipal(report.email());

        mockMvc
                .perform(
                        post(URI.create("http://" + slug + ".localhost/for-action/leave-requests/" + request.requireId() + "/approve"))
                                .with(user(principal))
                                .with(csrf()))
                .andExpect(status().isForbidden());
    }

    @Test
    void approve_selfApprovalAsAdmin_returns403() throws Exception {
        // BR-6: self-approval is forbidden unconditionally, even for an Admin deciding their own
        // request — requireApprovalAuthority checks this before the isAdmin branch.
        Employee admin = createEmployee("Self", "Admin", null);
        grantRole(admin, Role.ADMIN);
        // No manager, so booking routes to the Admin fallback (PRD §12.4 step 2) — that lookup
        // filters on AppUser.status ACTIVE, which createEmployee's activateForUser call doesn't
        // set (it only transitions the Employee's own status), so it needs a real activation too.
        run(
                () -> {
                    AppUser user = appUsers.findById(admin.userId()).orElseThrow();
                    user.acceptInvite("test-hash");
                    return appUsers.save(user);
                });
        LeaveRequest request = bookLeaveRequest(admin);
        UserDetails principal = loadPrincipal(admin.email());

        mockMvc
                .perform(
                        post(URI.create("http://" + slug + ".localhost/for-action/leave-requests/" + request.requireId() + "/approve"))
                                .with(user(principal))
                                .with(csrf()))
                .andExpect(status().isForbidden());
    }

    @Test
    void reject_asEmployee_returns403() throws Exception {
        Employee manager = createEmployee("Direct", "Manager", null);
        Employee report = createEmployee("Direct", "Report", manager.requireId());
        LeaveRequest request = bookLeaveRequest(report);
        UserDetails principal = loadPrincipal(report.email());

        mockMvc
                .perform(
                        post(URI.create("http://" + slug + ".localhost/for-action/leave-requests/" + request.requireId() + "/reject"))
                                .with(user(principal))
                                .with(csrf()))
                .andExpect(status().isForbidden());
    }

    @Test
    void reject_asUnrelatedManager_returns403() throws Exception {
        Employee actualManager = createEmployee("Direct", "Manager", null);
        Employee report = createEmployee("Direct", "Report", actualManager.requireId());
        LeaveRequest request = bookLeaveRequest(report);

        Employee unrelatedManager = createEmployee("Unrelated", "Manager", null);
        grantRole(unrelatedManager, Role.MANAGER);
        UserDetails principal = loadPrincipal(unrelatedManager.email());

        mockMvc
                .perform(
                        post(URI.create("http://" + slug + ".localhost/for-action/leave-requests/" + request.requireId() + "/reject"))
                                .with(user(principal))
                                .with(csrf()))
                .andExpect(status().isForbidden());
    }

    @Test
    void reject_selfApprovalAsAdmin_returns403() throws Exception {
        // BR-6 applies to reject() too, via the same requireApprovalAuthority guard as approve().
        Employee admin = createEmployee("Self", "Admin", null);
        grantRole(admin, Role.ADMIN);
        run(
                () -> {
                    AppUser user = appUsers.findById(admin.userId()).orElseThrow();
                    user.acceptInvite("test-hash");
                    return appUsers.save(user);
                });
        LeaveRequest request = bookLeaveRequest(admin);
        UserDetails principal = loadPrincipal(admin.email());

        mockMvc
                .perform(
                        post(URI.create("http://" + slug + ".localhost/for-action/leave-requests/" + request.requireId() + "/reject"))
                                .with(user(principal))
                                .with(csrf()))
                .andExpect(status().isForbidden());
    }

    private LeaveRequest bookLeaveRequest(Employee requester) {
        LocalDate start = LocalDate.now().with(TemporalAdjusters.next(DayOfWeek.MONDAY));
        return run(
                () ->
                        leaveRequestService.book(
                                requester.requireId(),
                                leaveTypeCache.requireId(),
                                start,
                                start,
                                false,
                                false,
                                null,
                                BASE_URL));
    }

    private LeaveType leaveTypeCache;

    private Employee createEmployee(String firstName, String lastName, java.util.UUID managerId) {
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
                                                LocalDate.now(),
                                                null,
                                                null,
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
