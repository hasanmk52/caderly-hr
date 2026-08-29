package com.caderly.caderlyhr.web;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.caderly.caderlyhr.TestcontainersConfiguration;
import com.caderly.caderlyhr.documents.DocumentVisibility;
import com.caderly.caderlyhr.documents.EmployeeDocumentService;
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
import java.nio.charset.StandardCharsets;
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
import org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder;

/**
 * PRD FR-3.9 / §26: self and Admin may upload/view/delete an employee's documents; a Manager
 * viewing a report's profile page must still be denied at these endpoints even though they can
 * view the page itself — the profile page hiding the tab is a UI nicety, not the real control
 * (CLAUDE.md §6 A01, and {@code documents.EmployeeDocumentService}'s own doc comment on this).
 * Denied document access is 404, not 403, matching {@code EmployeeService#requireOwnedBy}'s
 * anti-enumeration convention — an ownership mismatch must look identical to "doesn't exist".
 */
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("test")
@SpringBootTest
@AutoConfigureMockMvc
class ProfileDocumentAccessControlTest {

    private static final String BASE_URL = "https://acme.localhost";
    private static final String TENANT_NAME = "Acme";

    @Autowired private MockMvc mockMvc;
    @Autowired private TenantRepository tenants;
    @Autowired private EmployeeService employeeService;
    @Autowired private EmployeeDocumentService employeeDocuments;
    @Autowired private AppUserRepository appUsers;
    @Autowired private AppUserDetailsService userDetailsService;

    private String slug;
    private UUID tenantId;

    @BeforeEach
    void seedTenant() {
        slug = "docs-rbac" + UUID.randomUUID().toString().substring(0, 8);
        tenantId =
                TenantContext.runAsSystem(
                        "test: seed tenant", () -> tenants.save(new Tenant(slug, "Docs RBAC Co")).getId());
    }

    @Test
    void uploadDocument_asSelf_returns200() throws Exception {
        Employee self = createEmployee("Self", "Uploader");
        UserDetails principal = loadPrincipal(self.email());

        mockMvc
                .perform(
                        multipartUrl("/profile/" + self.requireId() + "/documents")
                                .file(pdf())
                                .with(user(principal))
                                .with(csrf()))
                .andExpect(status().isOk());
    }

    @Test
    void uploadDocument_forAnotherEmployee_asPlainEmployee_returns403() throws Exception {
        Employee uploader = createEmployee("Uploader", "One");
        Employee target = createEmployee("Target", "One");
        UserDetails principal = loadPrincipal(uploader.email());

        mockMvc
                .perform(
                        multipartUrl("/profile/" + target.requireId() + "/documents")
                                .file(pdf())
                                .with(user(principal))
                                .with(csrf()))
                .andExpect(status().isForbidden());
    }

    @Test
    void uploadDocument_onBehalf_asAdmin_returns200() throws Exception {
        Employee target = createEmployee("Target", "Two");
        Employee adminEmployee = createEmployee("Admin", "Person");
        grantRole(adminEmployee, Role.ADMIN);
        UserDetails adminPrincipal = loadPrincipal(adminEmployee.email());

        mockMvc
                .perform(
                        multipartUrl("/profile/" + target.requireId() + "/documents")
                                .file(pdf())
                                .with(user(adminPrincipal))
                                .with(csrf()))
                .andExpect(status().isOk());
    }

    @Test
    void uploadDocument_asManagerOfTarget_returns403() throws Exception {
        Employee target = createEmployee("Target", "Three");
        Employee manager = createEmployee("Manager", "One");
        grantRole(manager, Role.MANAGER);
        makeManagerOf(manager, target);
        UserDetails managerPrincipal = loadPrincipal(manager.email());

        mockMvc
                .perform(
                        multipartUrl("/profile/" + target.requireId() + "/documents")
                                .file(pdf())
                                .with(user(managerPrincipal))
                                .with(csrf()))
                .andExpect(status().isForbidden());
    }

    @Test
    void downloadDocument_asOwner_returns200() throws Exception {
        Employee owner = createEmployee("Owner", "Doc");
        UUID docId = uploadOwnDocument(owner);
        UserDetails principal = loadPrincipal(owner.email());

        mockMvc.perform(url("/profile/documents/" + docId + "/download").with(user(principal)))
                .andExpect(status().isOk());
    }

    @Test
    void downloadDocument_asDifferentEmployee_returns404() throws Exception {
        Employee owner = createEmployee("Owner", "Two");
        Employee other = createEmployee("Other", "Employee");
        UUID docId = uploadOwnDocument(owner);
        UserDetails otherPrincipal = loadPrincipal(other.email());

        mockMvc.perform(url("/profile/documents/" + docId + "/download").with(user(otherPrincipal)))
                .andExpect(status().isNotFound());
    }

    @Test
    void downloadDocument_asAdmin_returns200() throws Exception {
        Employee owner = createEmployee("Owner", "Three");
        Employee adminEmployee = createEmployee("Admin", "Two");
        grantRole(adminEmployee, Role.ADMIN);
        UUID docId = uploadOwnDocument(owner);
        UserDetails adminPrincipal = loadPrincipal(adminEmployee.email());

        mockMvc.perform(url("/profile/documents/" + docId + "/download").with(user(adminPrincipal)))
                .andExpect(status().isOk());
    }

    /**
     * The headline manager-exclusion case. This manager genuinely IS the owner's manager — proven
     * by the first assertion, that {@code getProfileForViewer} lets them load the report's profile
     * page — yet the document endpoint must still deny them, independent of that (see the class
     * doc comment).
     */
    @Test
    void downloadDocument_asManagerOfOwner_returns404() throws Exception {
        Employee owner = createEmployee("Owner", "Four");
        Employee manager = createEmployee("Manager", "Two");
        grantRole(manager, Role.MANAGER);
        makeManagerOf(manager, owner);
        UUID docId = uploadOwnDocument(owner);
        UserDetails managerPrincipal = loadPrincipal(manager.email());

        mockMvc.perform(url("/profile/" + owner.requireId()).with(user(managerPrincipal))).andExpect(status().isOk());
        mockMvc.perform(url("/profile/documents/" + docId + "/download").with(user(managerPrincipal)))
                .andExpect(status().isNotFound());
    }

    @Test
    void downloadDocument_adminOnlyVisibility_asOwningEmployee_returns404() throws Exception {
        Employee owner = createEmployee("Owner", "Five");
        Employee adminEmployee = createEmployee("Admin", "Three");
        grantRole(adminEmployee, Role.ADMIN);
        UUID docId =
                run(
                        () ->
                                employeeDocuments
                                        .uploadOnBehalfAndList(
                                                owner.requireId(),
                                                pdfMultipart(),
                                                DocumentVisibility.ADMIN_ONLY,
                                                adminEmployee.userId())
                                        .get(0)
                                        .requireId());
        UserDetails ownerPrincipal = loadPrincipal(owner.email());

        mockMvc.perform(url("/profile/documents/" + docId + "/download").with(user(ownerPrincipal)))
                .andExpect(status().isNotFound());
    }

    @Test
    void deleteDocument_asDifferentEmployee_returns404() throws Exception {
        Employee owner = createEmployee("Owner", "Six");
        Employee other = createEmployee("Other", "Two");
        UUID docId = uploadOwnDocument(owner);
        UserDetails otherPrincipal = loadPrincipal(other.email());

        mockMvc
                .perform(
                        delete(URI.create("http://" + slug + ".localhost/profile/documents/" + docId))
                                .with(user(otherPrincipal))
                                .with(csrf()))
                .andExpect(status().isNotFound());
    }

    @Test
    void deleteDocument_asOwner_returns200() throws Exception {
        Employee owner = createEmployee("Owner", "Seven");
        UUID docId = uploadOwnDocument(owner);
        UserDetails principal = loadPrincipal(owner.email());

        mockMvc
                .perform(
                        delete(URI.create("http://" + slug + ".localhost/profile/documents/" + docId))
                                .with(user(principal))
                                .with(csrf()))
                .andExpect(status().isOk());
    }

    private UUID uploadOwnDocument(Employee owner) {
        return run(
                () ->
                        employeeDocuments
                                .uploadOwnAndList(owner.requireId(), pdfMultipart(), owner.userId())
                                .get(0)
                                .requireId());
    }

    private MockMultipartFile pdf() {
        return new MockMultipartFile(
                "file", "passport.pdf", null, "%PDF-1.4\n%%EOF".getBytes(StandardCharsets.UTF_8));
    }

    private org.springframework.web.multipart.MultipartFile pdfMultipart() {
        return pdf();
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

    private void makeManagerOf(Employee manager, Employee report) {
        run(() -> employeeService.reassignManager(report.requireId(), manager.requireId()));
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

    private MockMultipartHttpServletRequestBuilder multipartUrl(String path) {
        return multipart(URI.create("http://" + slug + ".localhost" + path));
    }
}
