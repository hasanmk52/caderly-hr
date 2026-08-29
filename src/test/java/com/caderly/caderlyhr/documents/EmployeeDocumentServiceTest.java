package com.caderly.caderlyhr.documents;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.caderly.caderlyhr.common.NotFoundException;
import com.caderly.caderlyhr.people.Employee;
import com.caderly.caderlyhr.people.EmployeeForms;
import com.caderly.caderlyhr.people.EmployeeService;
import com.caderly.caderlyhr.tenantisolation.TenantIsolationTestBase;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.multipart.MultipartFile;

/**
 * Covers the self-vs-admin-on-behalf split and the visibility rules PRD FR-3.9 requires: an
 * ADMIN_ONLY document must be invisible to the employee it belongs to, and any caller who isn't
 * the owner or an Admin — including a stand-in for a Manager viewing a report's profile — must be
 * denied with the exact same NotFoundException a nonexistent id would produce (CLAUDE.md §6 A01).
 */
class EmployeeDocumentServiceTest extends TenantIsolationTestBase {

    private static final String BASE_URL = "https://acme.localhost";
    private static final String TENANT_NAME = "Acme";

    @Autowired private EmployeeDocumentService employeeDocuments;
    @Autowired private EmployeeService employeeService;

    @Test
    void uploadOwnAndList_setsEmployeePrivateVisibility() {
        Employee employee = seedEmployee();

        List<EmployeeDocument> fresh =
                asTenant(tenantA, () -> employeeDocuments.uploadOwnAndList(employee.requireId(), pdf(), employee.userId()));

        assertThat(fresh).hasSize(1);
        assertThat(fresh.get(0).visibility()).isEqualTo(DocumentVisibility.EMPLOYEE_PRIVATE);
    }

    @Test
    void uploadOnBehalfAndList_asAdmin_setsChosenVisibility() {
        Employee employee = seedEmployee();
        UUID adminUserId = seedEmployee().userId();

        List<EmployeeDocument> fresh =
                asTenant(
                        tenantA,
                        () ->
                                employeeDocuments.uploadOnBehalfAndList(
                                        employee.requireId(), pdf(), DocumentVisibility.ADMIN_ONLY, adminUserId));

        assertThat(fresh).hasSize(1);
        assertThat(fresh.get(0).visibility()).isEqualTo(DocumentVisibility.ADMIN_ONLY);
    }

    @Test
    void listVisibleTo_asNonAdmin_excludesAdminOnlyDocuments() {
        Employee employee = seedEmployee();
        UUID adminUserId = seedEmployee().userId();
        asTenant(
                tenantA,
                () ->
                        employeeDocuments.uploadOnBehalfAndList(
                                employee.requireId(), pdf(), DocumentVisibility.ADMIN_ONLY, adminUserId));

        List<EmployeeDocument> asSelf = asTenant(tenantA, () -> employeeDocuments.listVisibleTo(employee.requireId(), false));
        List<EmployeeDocument> asAdmin = asTenant(tenantA, () -> employeeDocuments.listVisibleTo(employee.requireId(), true));

        assertThat(asSelf).isEmpty();
        assertThat(asAdmin).hasSize(1);
    }

    @Test
    void download_ownEmployeePrivateDocument_returnsBytes() throws Exception {
        Employee employee = seedEmployee();
        byte[] content = "%PDF-1.4\npassport\n%%EOF".getBytes(StandardCharsets.UTF_8);
        MultipartFile file = new MockMultipartFile("file", "passport.pdf", null, content);
        List<EmployeeDocument> fresh =
                asTenant(tenantA, () -> employeeDocuments.uploadOwnAndList(employee.requireId(), file, employee.userId()));
        UUID docId = fresh.get(0).requireId();

        DownloadableFile downloaded =
                asTenant(tenantA, () -> employeeDocuments.download(docId, employee.requireId(), false));
        try (var in = downloaded.content()) {
            assertThat(in.readAllBytes()).isEqualTo(content);
        }
    }

    @Test
    void download_adminOnlyDocument_asOwningEmployee_throwsNotFound() {
        Employee employee = seedEmployee();
        UUID adminUserId = seedEmployee().userId();
        List<EmployeeDocument> fresh =
                asTenant(
                        tenantA,
                        () ->
                                employeeDocuments.uploadOnBehalfAndList(
                                        employee.requireId(), pdf(), DocumentVisibility.ADMIN_ONLY, adminUserId));
        UUID docId = fresh.get(0).requireId();

        assertThatThrownBy(() -> asTenant(tenantA, () -> employeeDocuments.download(docId, employee.requireId(), false)))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    void download_adminOnlyDocument_asAdmin_returnsIt() {
        Employee employee = seedEmployee();
        UUID adminUserId = seedEmployee().userId();
        List<EmployeeDocument> fresh =
                asTenant(
                        tenantA,
                        () ->
                                employeeDocuments.uploadOnBehalfAndList(
                                        employee.requireId(), pdf(), DocumentVisibility.ADMIN_ONLY, adminUserId));
        UUID docId = fresh.get(0).requireId();

        DownloadableFile downloaded = asTenant(tenantA, () -> employeeDocuments.download(docId, null, true));

        assertThat(downloaded.filename()).isEqualTo("handbook.pdf");
    }

    /** Stands in for a Manager viewing a report's profile: not the owner, not an Admin. */
    @Test
    void download_byADifferentEmployee_throwsNotFound() {
        Employee owner = seedEmployee();
        Employee otherCaller = seedEmployee();
        List<EmployeeDocument> fresh =
                asTenant(tenantA, () -> employeeDocuments.uploadOwnAndList(owner.requireId(), pdf(), owner.userId()));
        UUID docId = fresh.get(0).requireId();

        assertThatThrownBy(
                        () -> asTenant(tenantA, () -> employeeDocuments.download(docId, otherCaller.requireId(), false)))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    void deleteAndList_removesTheRowAndTheBytes() {
        Employee employee = seedEmployee();
        List<EmployeeDocument> fresh =
                asTenant(tenantA, () -> employeeDocuments.uploadOwnAndList(employee.requireId(), pdf(), employee.userId()));
        UUID docId = fresh.get(0).requireId();

        EmployeeDocumentService.DeletionResult afterDelete =
                asTenant(tenantA, () -> employeeDocuments.deleteAndList(docId, employee.requireId(), false));

        assertThat(afterDelete.employeeId()).isEqualTo(employee.requireId());
        assertThat(afterDelete.remaining()).isEmpty();
        assertThatThrownBy(() -> asTenant(tenantA, () -> employeeDocuments.download(docId, employee.requireId(), false)))
                .isInstanceOf(NotFoundException.class);
    }

    private Employee seedEmployee() {
        return asTenant(
                tenantA,
                () ->
                        employeeService.create(
                                new EmployeeForms.CreateEmployee(
                                        "Priya",
                                        "Shah",
                                        UUID.randomUUID() + "@example.test",
                                        null, null, null, null, null, null, null, null, null, null, null,
                                        null, null, null, null, null),
                                BASE_URL,
                                TENANT_NAME));
    }

    private MultipartFile pdf() {
        return new MockMultipartFile(
                "file", "handbook.pdf", null, "%PDF-1.4\n%%EOF".getBytes(StandardCharsets.UTF_8));
    }
}
