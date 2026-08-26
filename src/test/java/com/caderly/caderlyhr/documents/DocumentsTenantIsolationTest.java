package com.caderly.caderlyhr.documents;

import static org.assertj.core.api.Assertions.assertThat;

import com.caderly.caderlyhr.people.Employee;
import com.caderly.caderlyhr.people.EmployeeForms;
import com.caderly.caderlyhr.people.EmployeeService;
import com.caderly.caderlyhr.tenantisolation.TenantIsolationTestBase;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * CLAUDE.md §5 rule 8: every new tenant-scoped entity gets a test proving cross-tenant reads come
 * back empty, at both the Hibernate layer and — since native/raw JDBC bypasses {@code @TenantId}
 * entirely — the Postgres RLS layer itself (mirrors {@code TimeoffTenantIsolationTest}).
 */
class DocumentsTenantIsolationTest extends TenantIsolationTestBase {

    private static final String BASE_URL = "https://acme.localhost";
    private static final String TENANT_NAME = "Acme";

    @Autowired private CompanyFileRepository companyFiles;
    @Autowired private EmployeeDocumentRepository employeeDocuments;
    @Autowired private EmployeeService employeeService;
    @Autowired private PostgreSQLContainer postgres;

    @Test
    void companyFile_seededInTenantA_isInvisibleToTenantB() {
        Employee uploader = createEmployee(tenantA);
        CompanyFile saved =
                asTenant(
                        tenantA,
                        () ->
                                companyFiles.save(
                                        CompanyFile.create(
                                                "handbook.pdf",
                                                "application/pdf",
                                                1024,
                                                tenantA + "/" + UUID.randomUUID(),
                                                uploader.userId())));

        assertThat(asTenant(tenantA, () -> companyFiles.findAllByOrderByCreatedAtDesc())).hasSize(1);
        assertThat(asTenant(tenantB, () -> companyFiles.findById(saved.requireId()))).isEmpty();
    }

    @Test
    void employeeDocument_seededInTenantA_isInvisibleToTenantB() {
        Employee employee = createEmployee(tenantA);
        EmployeeDocument saved =
                asTenant(
                        tenantA,
                        () ->
                                employeeDocuments.save(
                                        EmployeeDocument.uploadOwn(
                                                employee.requireId(),
                                                "passport.pdf",
                                                "application/pdf",
                                                2048,
                                                tenantA + "/" + UUID.randomUUID(),
                                                employee.userId())));

        assertThat(
                        asTenant(
                                tenantA,
                                () -> employeeDocuments.findAllByEmployeeIdOrderByCreatedAtDesc(employee.requireId())))
                .hasSize(1);
        assertThat(asTenant(tenantB, () -> employeeDocuments.findById(saved.requireId()))).isEmpty();
        assertThat(
                        asTenant(
                                tenantB,
                                () -> employeeDocuments.findAllByEmployeeIdOrderByCreatedAtDesc(employee.requireId())))
                .isEmpty();
    }

    @Test
    void rawJdbc_withoutTenantSetting_rlsReturnsNoCompanyFiles() throws Exception {
        Employee uploader = createEmployee(tenantA);
        asTenant(
                tenantA,
                () ->
                        companyFiles.save(
                                CompanyFile.create(
                                        "handbook.pdf",
                                        "application/pdf",
                                        1024,
                                        tenantA + "/" + UUID.randomUUID(),
                                        uploader.userId())));

        assertThat(countAsRestrictedRole("company_file", null)).isZero();
        assertThat(countAsRestrictedRole("company_file", tenantA)).isEqualTo(1);
    }

    @Test
    void rawJdbc_withoutTenantSetting_rlsReturnsNoEmployeeDocuments() throws Exception {
        Employee employee = createEmployee(tenantA);
        asTenant(
                tenantA,
                () ->
                        employeeDocuments.save(
                                EmployeeDocument.uploadOwn(
                                        employee.requireId(),
                                        "passport.pdf",
                                        "application/pdf",
                                        2048,
                                        tenantA + "/" + UUID.randomUUID(),
                                        employee.userId())));

        assertThat(countAsRestrictedRole("employee_document", null)).isZero();
        assertThat(countAsRestrictedRole("employee_document", tenantA)).isEqualTo(1);
    }

    private Employee createEmployee(UUID tenantId) {
        return asTenant(
                tenantId,
                () ->
                        employeeService.create(
                                new EmployeeForms.CreateEmployee(
                                        "Priya",
                                        "Shah",
                                        UUID.randomUUID() + "@example.test",
                                        null,
                                        null,
                                        null,
                                        null,
                                        null,
                                        null,
                                        null,
                                        null,
                                        null,
                                        null,
                                        null,
                                        null,
                                        null,
                                        null,
                                        null,
                                        null),
                                BASE_URL,
                                TENANT_NAME));
    }

    private int countAsRestrictedRole(String table, UUID tenantId) throws Exception {
        try (Connection connection =
                DriverManager.getConnection(postgres.getJdbcUrl(), "rls_probe", "rls_probe")) {
            connection.setAutoCommit(false);
            try {
                if (tenantId != null) {
                    try (PreparedStatement ps =
                            connection.prepareStatement("SELECT set_config('app.tenant_id', ?, true)")) {
                        ps.setString(1, tenantId.toString());
                        ps.execute();
                    }
                }
                try (PreparedStatement ps =
                                connection.prepareStatement("SELECT count(*) FROM caderly_hr." + table);
                        ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    return rs.getInt(1);
                }
            } finally {
                connection.rollback();
            }
        }
    }
}
