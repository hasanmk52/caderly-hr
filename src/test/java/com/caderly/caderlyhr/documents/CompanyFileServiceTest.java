package com.caderly.caderlyhr.documents;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.caderly.caderlyhr.common.NotFoundException;
import com.caderly.caderlyhr.common.ValidationException;
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

class CompanyFileServiceTest extends TenantIsolationTestBase {

    private static final String BASE_URL = "https://acme.localhost";
    private static final String TENANT_NAME = "Acme";

    @Autowired private CompanyFileService companyFiles;
    @Autowired private EmployeeService employeeService;

    @Test
    void uploadAndList_validPdf_appearsInTheList() {
        UUID uploader = seedUploader();

        List<CompanyFile> fresh = asTenant(tenantA, () -> companyFiles.uploadAndList(pdf(), uploader));

        assertThat(fresh).hasSize(1);
        assertThat(fresh.get(0).name()).isEqualTo("handbook.pdf");
        assertThat(fresh.get(0).mime()).isEqualTo("application/pdf");
    }

    @Test
    void uploadAndList_invalidType_throwsAndPersistsNothing() {
        UUID uploader = seedUploader();
        MultipartFile script =
                new MockMultipartFile("file", "script.js", null, "x".getBytes(StandardCharsets.UTF_8));

        assertThatThrownBy(() -> asTenant(tenantA, () -> companyFiles.uploadAndList(script, uploader)))
                .isInstanceOf(ValidationException.class);
        assertThat(asTenant(tenantA, companyFiles::listAll)).isEmpty();
    }

    @Test
    void download_afterUpload_returnsTheExactBytes() throws Exception {
        UUID uploader = seedUploader();
        byte[] content = "%PDF-1.4\nhello\n%%EOF".getBytes(StandardCharsets.UTF_8);
        MultipartFile file = new MockMultipartFile("file", "handbook.pdf", null, content);

        List<CompanyFile> fresh = asTenant(tenantA, () -> companyFiles.uploadAndList(file, uploader));
        UUID id = fresh.get(0).requireId();

        DownloadableFile downloaded = asTenant(tenantA, () -> companyFiles.download(id));
        try (var in = downloaded.content()) {
            assertThat(in.readAllBytes()).isEqualTo(content);
            assertThat(downloaded.filename()).isEqualTo("handbook.pdf");
        }
    }

    @Test
    void deleteAndList_removesTheRowAndTheBytes() {
        UUID uploader = seedUploader();
        List<CompanyFile> fresh = asTenant(tenantA, () -> companyFiles.uploadAndList(pdf(), uploader));
        UUID id = fresh.get(0).requireId();

        List<CompanyFile> afterDelete = asTenant(tenantA, () -> companyFiles.deleteAndList(id));

        assertThat(afterDelete).isEmpty();
        assertThatThrownBy(() -> asTenant(tenantA, () -> companyFiles.download(id)))
                .isInstanceOf(NotFoundException.class);
    }

    private UUID seedUploader() {
        Employee employee =
                asTenant(
                        tenantA,
                        () ->
                                employeeService.create(
                                        new EmployeeForms.CreateEmployee(
                                                "Admin",
                                                "Person",
                                                UUID.randomUUID() + "@example.test",
                                                null, null, null, null, null, null, null, null, null, null, null,
                                                null, null, null, null, null),
                                        BASE_URL,
                                        TENANT_NAME));
        return employee.userId();
    }

    private MultipartFile pdf() {
        return new MockMultipartFile(
                "file", "handbook.pdf", null, "%PDF-1.4\n%%EOF".getBytes(StandardCharsets.UTF_8));
    }
}
