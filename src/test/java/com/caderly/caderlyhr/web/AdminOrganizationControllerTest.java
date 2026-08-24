package com.caderly.caderlyhr.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.caderly.caderlyhr.TestcontainersConfiguration;
import com.caderly.caderlyhr.org.Division;
import com.caderly.caderlyhr.org.DivisionRepository;
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
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Exercises the htmx contract described in {@link AdminOrganizationController}'s Javadoc: a
 * successful mutation carries an {@code HX-Trigger} toast header and the response contains the
 * fresh row; a validation failure re-renders the form with the error and no trigger header.
 * {@link com.caderly.caderlyhr.org.OrganizationServiceTest} already covers the business rules
 * (delete-or-archive branching, uniqueness) at the service layer — this class only proves the
 * controller wires those rules to the right HTTP response shape.
 */
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("test")
@SpringBootTest
@AutoConfigureMockMvc
class AdminOrganizationControllerTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private TenantRepository tenants;
    @Autowired private DivisionRepository divisions;
    @Autowired private TransactionTemplate transactions;

    private String slug;
    private UUID tenantId;

    @BeforeEach
    void seedTenant() {
        slug = "org-ctrl" + UUID.randomUUID().toString().substring(0, 8);
        tenantId =
                TenantContext.runAsSystem(
                        "test: seed tenant",
                        () -> transactions.execute(status -> tenants.save(new Tenant(slug, "Org Ctrl Co")).getId()));
    }

    @Test
    void createDivision_asAdmin_returns200WithToastHeaderAndNewRow() throws Exception {
        mockMvc
                .perform(
                        post(URI.create("http://" + slug + ".localhost/admin/divisions"))
                                .param("name", "Engineering")
                                .with(admin())
                                .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(header().string("HX-Trigger", containsString("Division created")))
                .andExpect(content().string(containsString("Engineering")));
    }

    @Test
    void createDivision_withDuplicateName_reRendersFormWithErrorAndNoToast() throws Exception {
        seedDivision("Engineering");

        mockMvc
                .perform(
                        post(URI.create("http://" + slug + ".localhost/admin/divisions"))
                                .param("name", "Engineering")
                                .with(admin())
                                .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(header().doesNotExist("HX-Trigger"))
                .andExpect(content().string(containsString("already exists")));
    }

    @Test
    void deleteDivision_withNoDepartments_hardDeletesAndToastsDeleted() throws Exception {
        UUID id = seedDivision("Temporary");

        mockMvc
                .perform(
                        delete(URI.create("http://" + slug + ".localhost/admin/divisions/" + id))
                                .with(admin())
                                .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(header().string("HX-Trigger", containsString("Division deleted")))
                .andExpect(content().string(org.hamcrest.Matchers.not(containsString("Temporary"))));
    }

    private UUID seedDivision(String name) {
        TenantContext.set(tenantId);
        try {
            return transactions.execute(status -> divisions.save(Division.create(name, null)).getId());
        } finally {
            TenantContext.clear();
        }
    }

    private static SecurityMockMvcRequestPostProcessors.UserRequestPostProcessor admin() {
        return user("admin@org.test").roles("ADMIN");
    }
}
