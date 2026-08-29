package com.caderly.caderlyhr.web;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * CLAUDE.md §8: one 200 test and one 403 test per protected endpoint per role. Permissions per PRD
 * §26: viewing/downloading Company Files is any signed-in tenant member; uploading and deleting
 * are Admin-only.
 */
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("test")
@SpringBootTest
@AutoConfigureMockMvc
class FilesAccessControlTest {

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
        slug = "files-rbac" + UUID.randomUUID().toString().substring(0, 8);
        tenantId =
                TenantContext.runAsSystem(
                        "test: seed tenant", () -> tenants.save(new Tenant(slug, "Files RBAC Co")).getId());
    }

    @Test
    void filesPage_asEmployee_returns200() throws Exception {
        mockMvc.perform(url("/files").with(user("employee@files.test").roles("EMPLOYEE")))
                .andExpect(status().isOk());
    }

    @Test
    void filesPage_whenAnonymous_redirectsToLogin() throws Exception {
        mockMvc.perform(url("/files")).andExpect(status().is3xxRedirection());
    }

    @Test
    void uploadFile_asAdmin_returns200() throws Exception {
        Employee adminEmployee = createEmployee("Admin", "Person");
        grantRole(adminEmployee, Role.ADMIN);
        UserDetails adminPrincipal = loadPrincipal(adminEmployee.email());

        mockMvc
                .perform(
                        multipartUrl("/files/upload")
                                .file(pdf())
                                .with(user(adminPrincipal))
                                .with(csrf()))
                .andExpect(status().isOk());
    }

    @Test
    void uploadFile_asEmployee_returns403() throws Exception {
        mockMvc
                .perform(
                        multipartUrl("/files/upload")
                                .file(pdf())
                                .with(user("employee@files.test").roles("EMPLOYEE"))
                                .with(csrf()))
                .andExpect(status().isForbidden());
    }

    @Test
    void uploadFile_asManager_returns403() throws Exception {
        mockMvc
                .perform(
                        multipartUrl("/files/upload")
                                .file(pdf())
                                .with(user("manager@files.test").roles("MANAGER"))
                                .with(csrf()))
                .andExpect(status().isForbidden());
    }

    @Test
    void deleteFile_asEmployee_returns403() throws Exception {
        mockMvc
                .perform(
                        delete(URI.create("http://" + slug + ".localhost/files/" + UUID.randomUUID()))
                                .with(user("employee@files.test").roles("EMPLOYEE"))
                                .with(csrf()))
                .andExpect(status().isForbidden());
    }

    @Test
    void downloadFile_whenAnonymous_redirectsToLogin() throws Exception {
        mockMvc
                .perform(url("/files/" + UUID.randomUUID() + "/download"))
                .andExpect(status().is3xxRedirection());
    }

    private MockMultipartFile pdf() {
        return new MockMultipartFile(
                "file", "handbook.pdf", null, "%PDF-1.4\n%%EOF".getBytes(StandardCharsets.UTF_8));
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

    private MockHttpServletRequestBuilder url(String path) {
        return get(URI.create("http://" + slug + ".localhost" + path));
    }

    private org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder multipartUrl(
            String path) {
        return multipart(URI.create("http://" + slug + ".localhost" + path));
    }
}
