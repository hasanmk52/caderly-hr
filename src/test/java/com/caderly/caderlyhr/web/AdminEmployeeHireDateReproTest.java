package com.caderly.caderlyhr.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.caderly.caderlyhr.TestcontainersConfiguration;
import com.caderly.caderlyhr.people.Employee;
import com.caderly.caderlyhr.people.EmployeeRepository;
import com.caderly.caderlyhr.tenant.Tenant;
import com.caderly.caderlyhr.tenant.TenantContext;
import com.caderly.caderlyhr.tenant.TenantRepository;
import java.net.URI;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Regression coverage: {@code hireDate} (and every other {@code LocalDate} form field on the
 * Employee admin form) must round-trip through an HTML5 {@code <input type="date">}. Without
 * {@code @DateTimeFormat(iso = ISO.DATE)}, Spring's default locale-based printer renders
 * {@code LocalDate} as e.g. {@code "6/1/21"} (US short style) instead of {@code "2021-06-01"} —
 * a browser silently treats that as an invalid {@code type="date"} value and blanks the field,
 * even though the raw HTML attribute and the database both hold the correct date.
 */
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("test")
@SpringBootTest
@AutoConfigureMockMvc
class AdminEmployeeHireDateReproTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private TenantRepository tenants;
    @Autowired private EmployeeRepository employeeRepository;
    @Autowired private TransactionTemplate transactions;

    private String slug;
    private UUID tenantId;

    @BeforeEach
    void seedTenant() {
        slug = "hiredate" + UUID.randomUUID().toString().substring(0, 8);
        tenantId =
                TenantContext.runAsSystem(
                        "test: seed tenant",
                        () -> transactions.execute(status -> tenants.save(new Tenant(slug, "Hire Date Co")).getId()));
    }

    @Test
    void patchWithHireDate_reRendersFormWithHireDateInIsoFormat() throws Exception {
        mockMvc
                .perform(
                        post(URI.create("http://" + slug + ".localhost/admin/employees"))
                                .param("firstName", "Jane")
                                .param("lastName", "Doe")
                                .param("email", "jane@hiredate.test")
                                .param("hireDate", "2020-01-15")
                                .with(admin())
                                .with(csrf()))
                .andExpect(status().isOk());

        UUID employeeId = findEmployeeId();

        MvcResult result =
                mockMvc
                        .perform(
                                patch(URI.create("http://" + slug + ".localhost/admin/employees/" + employeeId))
                                        .param("firstName", "Jane")
                                        .param("lastName", "Doe")
                                        .param("email", "jane@hiredate.test")
                                        .param("hireDate", "2021-06-01")
                                        .with(admin())
                                        .with(csrf()))
                        .andExpect(status().isOk())
                        .andExpect(content().string(org.hamcrest.Matchers.containsString(
                                "id=\"edit-hireDate\" name=\"hireDate\" value=\"2021-06-01\"")))
                        .andExpect(content().string(org.hamcrest.Matchers.containsString(
                                "hx-patch=\"/admin/employees/" + employeeId + "\"")))
                        .andReturn();

        // Regression guard for the redisplay bug: not just present in HTML, but the DB write
        // the user reported checking actually happened too.
        Employee reloaded = findEmployee(employeeId);
        assertThat(reloaded.hireDate()).isEqualTo(LocalDate.of(2021, 6, 1));
        assertThat(result.getResponse().getContentAsString()).doesNotContain("6/1/21");
    }

    private UUID findEmployeeId() {
        TenantContext.set(tenantId);
        try {
            List<Employee> all =
                    transactions.execute(status -> employeeRepository.findAllByOrderByLastNameAscFirstNameAsc());
            return all.get(0).requireId();
        } finally {
            TenantContext.clear();
        }
    }

    private Employee findEmployee(UUID employeeId) {
        TenantContext.set(tenantId);
        try {
            return transactions.execute(status -> employeeRepository.findById(employeeId).orElseThrow());
        } finally {
            TenantContext.clear();
        }
    }

    private static SecurityMockMvcRequestPostProcessors.UserRequestPostProcessor admin() {
        return user("admin@hiredate.test").roles("ADMIN");
    }
}
