package com.caderly.caderlyhr.web;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.caderly.caderlyhr.TestcontainersConfiguration;
import com.caderly.caderlyhr.documents.CompanyFileService;
import com.caderly.caderlyhr.identity.AppUser;
import com.caderly.caderlyhr.identity.AppUserDetailsService;
import com.caderly.caderlyhr.identity.AppUserRepository;
import com.caderly.caderlyhr.identity.Role;
import com.caderly.caderlyhr.org.Department;
import com.caderly.caderlyhr.org.DepartmentRepository;
import com.caderly.caderlyhr.org.Division;
import com.caderly.caderlyhr.org.DivisionRepository;
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
import com.caderly.caderlyhr.timeoff.PublicHolidayService;
import java.math.BigDecimal;
import java.net.URI;
import java.time.LocalDate;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

/**
 * PRD §24.2's Home dashboard: the shell greeting/grid, and each of the six widget fragment
 * endpoints (sub-phase 1.9 / ADR 0015). Uses a real {@code AppUserPrincipal} (via {@link
 * AppUserDetailsService}), not the generic {@code user(String)} post-processor — {@code
 * HomeController} binds {@code @AuthenticationPrincipal AppUserPrincipal}, which only resolves
 * against the real type (see {@link ProfileAccessControlTest}'s javadoc for the same gotcha).
 */
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("test")
@SpringBootTest
@AutoConfigureMockMvc
class HomeControllerTest {

    private static final String BASE_URL = "https://acme.localhost";
    private static final String TENANT_NAME = "Acme";

    @Autowired private MockMvc mockMvc;
    @Autowired private TenantRepository tenants;
    @Autowired private EmployeeService employeeService;
    @Autowired private AppUserRepository appUsers;
    @Autowired private AppUserDetailsService userDetailsService;
    @Autowired private LeaveTypeService leaveTypeService;
    @Autowired private LeaveRequestService leaveRequestService;
    @Autowired private PublicHolidayService publicHolidayService;
    @Autowired private CompanyFileService companyFileService;
    @Autowired private DivisionRepository divisions;
    @Autowired private DepartmentRepository departments;

    private String slug;
    private UUID tenantId;

    @BeforeEach
    void seedTenant() {
        slug = "home" + UUID.randomUUID().toString().substring(0, 8);
        tenantId =
                TenantContext.runAsSystem(
                        "test: seed tenant", () -> tenants.save(new Tenant(slug, "Home Co")).getId());
    }

    @Test
    void home_asEmployeeWithLinkedProfile_greetsByFirstName() throws Exception {
        Employee employee = createEmployee("Jane", "Doe");
        UserDetails principal = loadPrincipal(employee.email());

        mockMvc
                .perform(url("/").with(user(principal)))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Welcome, Jane!")));
    }

    /**
     * Mirrors an Admin-only account with no {@link Employee} row — {@code DevDataSeeder}'s dev
     * bootstrap admin is exactly this shape (see CLAUDE.md §5's PRD §5 note that this should never
     * happen in production). The greeting must degrade, not fail the page.
     */
    @Test
    void home_withNoLinkedEmployee_showsGenericGreeting() throws Exception {
        UserDetails principal = createAdminWithNoEmployee("admin-no-profile@home.test");

        mockMvc
                .perform(url("/").with(user(principal)))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Welcome!")))
                .andExpect(content().string(org.hamcrest.Matchers.not(containsString("Welcome, "))));
    }

    @Test
    void home_rendersSixIndependentlyLoadedWidgetContainers() throws Exception {
        Employee employee = createEmployee("Jane", "Doe");
        UserDetails principal = loadPrincipal(employee.email());

        mockMvc
                .perform(url("/").with(user(principal)))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("hx-get=\"/widgets/book-time-off\"")))
                .andExpect(content().string(containsString("hx-get=\"/widgets/my-peers\"")))
                .andExpect(content().string(containsString("hx-get=\"/widgets/time-off-today\"")))
                .andExpect(content().string(containsString("hx-get=\"/widgets/my-days-off\"")))
                .andExpect(content().string(containsString("hx-get=\"/widgets/upcoming-holidays\"")))
                .andExpect(content().string(containsString("hx-get=\"/widgets/resources\"")));
    }

    @Test
    void bookTimeOffWidget_withGrantedBalance_showsCard() throws Exception {
        run(
                () ->
                        leaveTypeService.create(
                                "Annual", null, null, true, true, false, true, new BigDecimal("30"), null));
        Employee employee = createEmployee("Priya", "Shah", LocalDate.now());
        UserDetails principal = loadPrincipal(employee.email());

        mockMvc
                .perform(url("/widgets/book-time-off").with(user(principal)))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Annual")));
    }

    @Test
    void bookTimeOffWidget_withNoLeaveTypesConfigured_showsEmptyState() throws Exception {
        Employee employee = createEmployee("Priya", "Shah");
        UserDetails principal = loadPrincipal(employee.email());

        mockMvc
                .perform(url("/widgets/book-time-off").with(user(principal)))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("No leave types are set up yet.")));
    }

    @Test
    void myPeersWidget_withSameDepartmentPeer_showsThem() throws Exception {
        UUID departmentId = seedDepartment();
        Employee self = createEmployeeInDepartment("Jane", "Doe", departmentId);
        createEmployeeInDepartment("Peer", "One", departmentId);
        UserDetails principal = loadPrincipal(self.email());

        mockMvc
                .perform(url("/widgets/my-peers").with(user(principal)))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("My Peers")))
                .andExpect(content().string(org.hamcrest.Matchers.not(containsString("No peers to show yet."))));
    }

    @Test
    void myPeersWidget_withNoPeers_showsEmptyState() throws Exception {
        Employee self = createEmployee("Lone", "Wolf");
        UserDetails principal = loadPrincipal(self.email());

        mockMvc
                .perform(url("/widgets/my-peers").with(user(principal)))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("No peers to show yet.")));
    }

    @Test
    void timeOffTodayWidget_withNoCoworkers_showsEmptyState() throws Exception {
        UserDetails principal = createAdminWithNoEmployee("admin-no-coworkers@home.test");

        mockMvc
                .perform(url("/widgets/time-off-today").with(user(principal)))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("No coworkers to show yet.")));
    }

    @Test
    void timeOffTodayWidget_withEveryoneIn_showsAllInMessage() throws Exception {
        Employee employee = createEmployee("Jane", "Doe");
        UserDetails principal = loadPrincipal(employee.email());

        mockMvc
                .perform(url("/widgets/time-off-today").with(user(principal)))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Everyone&#39;s in today.")));
    }

    @Test
    void timeOffTodayWidget_withSomeoneOutToday_showsThem() throws Exception {
        LeaveType annual =
                run(
                        () ->
                                leaveTypeService.create(
                                        "Annual", null, null, true, true, false, true, new BigDecimal("30"), null));
        Employee requester = createEmployee("Out", "Today", LocalDate.now());
        Employee admin = createAdminEmployee("Approving", "Admin");
        LeaveRequest request =
                run(
                        () ->
                                leaveRequestService.book(
                                        requester.requireId(),
                                        annual.requireId(),
                                        LocalDate.now(),
                                        LocalDate.now(),
                                        false,
                                        false,
                                        null,
                                        BASE_URL));
        run(
                () ->
                        leaveRequestService.approve(
                                request.requireId(), admin.userId(), admin.requireId(), admin.fullName(), true, null));
        UserDetails principal = loadPrincipal(admin.email());

        mockMvc
                .perform(url("/widgets/time-off-today").with(user(principal)))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Out Today")));
    }

    @Test
    void myDaysOffWidget_withUpcomingApprovedLeave_showsIt() throws Exception {
        LeaveType annual =
                run(
                        () ->
                                leaveTypeService.create(
                                        "Annual", null, null, true, true, false, true, new BigDecimal("30"), null));
        Employee requester = createEmployee("Jane", "Doe", LocalDate.now());
        Employee admin = createAdminEmployee("Approving", "Admin");
        LeaveRequest request =
                run(
                        () ->
                                leaveRequestService.book(
                                        requester.requireId(),
                                        annual.requireId(),
                                        LocalDate.now(),
                                        LocalDate.now(),
                                        false,
                                        false,
                                        null,
                                        BASE_URL));
        run(
                () ->
                        leaveRequestService.approve(
                                request.requireId(), admin.userId(), admin.requireId(), admin.fullName(), true, null));
        UserDetails principal = loadPrincipal(requester.email());

        mockMvc
                .perform(url("/widgets/my-days-off").with(user(principal)))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Annual")));
    }

    @Test
    void myDaysOffWidget_withNoUpcomingLeave_showsEmptyState() throws Exception {
        Employee employee = createEmployee("Jane", "Doe");
        UserDetails principal = loadPrincipal(employee.email());

        mockMvc
                .perform(url("/widgets/my-days-off").with(user(principal)))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Nothing booked yet.")));
    }

    @Test
    void upcomingHolidaysWidget_withHolidayConfigured_showsIt() throws Exception {
        run(() -> publicHolidayService.create(LocalDate.now().plusDays(10), "Founders Day"));
        Employee employee = createEmployee("Jane", "Doe");
        UserDetails principal = loadPrincipal(employee.email());

        mockMvc
                .perform(url("/widgets/upcoming-holidays").with(user(principal)))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Founders Day")));
    }

    @Test
    void upcomingHolidaysWidget_withNoHolidaysConfigured_showsEmptyState() throws Exception {
        Employee employee = createEmployee("Jane", "Doe");
        UserDetails principal = loadPrincipal(employee.email());

        mockMvc
                .perform(url("/widgets/upcoming-holidays").with(user(principal)))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("No holidays configured yet.")));
    }

    @Test
    void resourcesWidget_withCompanyFileUploaded_showsIt() throws Exception {
        Employee employee = createEmployee("Jane", "Doe");
        run(
                () ->
                        companyFileService.uploadAndList(
                                new MockMultipartFile(
                                        "file", "Handbook.pdf", null, "%PDF-1.4\n%%EOF".getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                                employee.userId()));
        UserDetails principal = loadPrincipal(employee.email());

        mockMvc
                .perform(url("/widgets/resources").with(user(principal)))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Handbook.pdf")));
    }

    @Test
    void resourcesWidget_withNoFiles_showsEmptyState() throws Exception {
        Employee employee = createEmployee("Jane", "Doe");
        UserDetails principal = loadPrincipal(employee.email());

        mockMvc
                .perform(url("/widgets/resources").with(user(principal)))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("No company files yet.")));
    }

    private Employee createEmployee(String firstName, String lastName) {
        return createEmployee(firstName, lastName, null);
    }

    private Employee createEmployee(String firstName, String lastName, LocalDate hireDate) {
        return createEmployee(firstName, lastName, hireDate, null);
    }

    private Employee createEmployeeInDepartment(String firstName, String lastName, UUID departmentId) {
        return createEmployee(firstName, lastName, LocalDate.now(), departmentId);
    }

    private Employee createEmployee(String firstName, String lastName, LocalDate hireDate, UUID departmentId) {
        Employee employee =
                run(
                        () ->
                                employeeService.create(
                                        new EmployeeForms.CreateEmployee(
                                                firstName,
                                                lastName,
                                                UUID.randomUUID() + "@example.test",
                                                null, // employeeCode
                                                null, // phone
                                                null, // birthDate
                                                null, // gender
                                                null, // maritalStatus
                                                null, // nationality
                                                null, // citizenship
                                                hireDate,
                                                null, // employmentType
                                                departmentId,
                                                null, // managerId
                                                null, // jobTitle
                                                null, // workLocation
                                                null, // workingHoursPerDay
                                                null, // currency
                                                null), // baseCompensation
                                        BASE_URL,
                                        TENANT_NAME));
        run(() -> employeeService.activateForUser(employee.userId()));
        return run(() -> employeeService.require(employee.requireId()));
    }

    private Employee createAdminEmployee(String firstName, String lastName) {
        Employee admin = createEmployee(firstName, lastName);
        run(
                () -> {
                    AppUser user = appUsers.findById(admin.userId()).orElseThrow();
                    user.grant(Role.ADMIN);
                    user.acceptInvite("test-hash");
                    return appUsers.save(user);
                });
        return admin;
    }

    private UUID seedDepartment() {
        return run(
                () -> {
                    Division division = divisions.save(Division.create("Div-" + UUID.randomUUID(), null));
                    Department department =
                            departments.save(Department.create("Dept-" + UUID.randomUUID(), null, division));
                    return department.requireId();
                });
    }

    private UserDetails createAdminWithNoEmployee(String email) {
        run(
                () -> {
                    AppUser admin = AppUser.active(email, "{noop}unused");
                    admin.grant(Role.ADMIN);
                    return appUsers.save(admin);
                });
        return loadPrincipal(email);
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
