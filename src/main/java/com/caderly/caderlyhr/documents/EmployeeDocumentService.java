package com.caderly.caderlyhr.documents;

import com.caderly.caderlyhr.common.NotFoundException;
import com.caderly.caderlyhr.common.ValidationException;
import com.caderly.caderlyhr.storage.FileStorage;
import com.caderly.caderlyhr.tenant.TenantContext;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

/**
 * Per-employee documents (PRD FR-3.9, §6.7 FR-7.2). Every read and mutation here takes the
 * caller's own identity as plain parameters ({@code callerEmployeeId}, {@code callerIsAdmin}) —
 * never an {@code AppUserPrincipal} or {@code Employee} — mirroring {@code
 * timeoff.LeaveRequestService}'s shape and keeping this package from depending on {@code
 * identity}/{@code people} at all.
 *
 * <p>{@link #uploadOwnAndList} and {@link #uploadOnBehalfAndList} are deliberately separate
 * methods, not one method with a {@code visibility} parameter defaulted by role: the self path
 * has no visibility parameter to bind from a request at all, so a self-uploader's form has no
 * field that could escalate their own document to {@code ADMIN_ONLY} or otherwise (there would be
 * nothing to tamper with even if the controller trusted the request body, which it does not).
 */
@Service
public class EmployeeDocumentService {

    private static final Logger log = LoggerFactory.getLogger(EmployeeDocumentService.class);

    private final EmployeeDocumentRepository documents;
    private final FileStorage storage;
    private final UploadValidator validator;

    EmployeeDocumentService(EmployeeDocumentRepository documents, FileStorage storage, UploadValidator validator) {
        this.documents = documents;
        this.storage = storage;
        this.validator = validator;
    }

    /**
     * Admin sees every document, including {@code ADMIN_ONLY}; anyone else sees only {@code
     * EMPLOYEE_PRIVATE} ones. This does not by itself prove the non-admin caller IS {@code
     * employeeId} — callers only reach this after confirming that themselves (see {@code
     * web.ProfileController}, which never renders this list for anyone but the owning employee or
     * an Admin).
     */
    @Transactional(readOnly = true)
    public List<EmployeeDocument> listVisibleTo(UUID employeeId, boolean callerIsAdmin) {
        List<EmployeeDocument> all = documents.findAllByEmployeeIdOrderByCreatedAtDesc(employeeId);
        if (callerIsAdmin) {
            return all;
        }
        return all.stream().filter(doc -> doc.visibility() == DocumentVisibility.EMPLOYEE_PRIVATE).toList();
    }

    @Transactional
    public List<EmployeeDocument> uploadOwnAndList(UUID employeeId, MultipartFile file, UUID uploaderUserId) {
        store(file, doc -> EmployeeDocument.uploadOwn(employeeId, doc.name(), doc.mime(), doc.size(), doc.key(), uploaderUserId));
        return listVisibleTo(employeeId, false);
    }

    @Transactional
    public List<EmployeeDocument> uploadOnBehalfAndList(
            UUID employeeId, MultipartFile file, DocumentVisibility visibility, UUID uploaderUserId) {
        store(
                file,
                doc ->
                        EmployeeDocument.uploadOnBehalf(
                                employeeId, doc.name(), doc.mime(), doc.size(), doc.key(), visibility, uploaderUserId));
        return listVisibleTo(employeeId, true);
    }

    @Transactional(readOnly = true)
    public DownloadableFile download(UUID docId, @Nullable UUID callerEmployeeId, boolean callerIsAdmin) {
        EmployeeDocument doc = requireVisibleTo(docId, callerEmployeeId, callerIsAdmin);
        return new DownloadableFile(doc.name(), doc.mime(), doc.sizeBytes(), storage.open(doc.storageKey()));
    }

    /**
     * Row deleted before bytes, same reasoning as {@code CompanyFileService#deleteAndList}.
     * Returns the owner's id alongside the refreshed list because a caller re-rendering a profile
     * tab has no other way to know whose tab to repopulate once the deleted document could have
     * been the owner's last one (an empty list carries no id to recover it from).
     */
    @Transactional
    public DeletionResult deleteAndList(UUID docId, @Nullable UUID callerEmployeeId, boolean callerIsAdmin) {
        EmployeeDocument doc = requireVisibleTo(docId, callerEmployeeId, callerIsAdmin);
        UUID employeeId = doc.employeeId();
        documents.delete(doc);
        storage.delete(doc.storageKey());
        log.info("Deleted employee document {}", docId);
        return new DeletionResult(employeeId, listVisibleTo(employeeId, callerIsAdmin));
    }

    public record DeletionResult(UUID employeeId, List<EmployeeDocument> remaining) {}

    /**
     * {@code NotFoundException}, never {@code AccessDeniedException}: an ownership or visibility
     * mismatch must look identical to "doesn't exist" (mirrors {@code
     * people.EmployeeService#requireOwnedBy}). This is also what keeps a Manager out — {@code
     * web.ProfileController} lets a Manager view a report's profile page, but a Manager's own
     * {@code callerEmployeeId} never equals the report's, so it falls through to this exception
     * exactly like a stranger's request would, regardless of what the profile page itself shows.
     */
    private EmployeeDocument requireVisibleTo(UUID docId, @Nullable UUID callerEmployeeId, boolean callerIsAdmin) {
        EmployeeDocument doc = require(docId);
        if (callerIsAdmin) {
            return doc;
        }
        if (callerEmployeeId != null
                && doc.employeeId().equals(callerEmployeeId)
                && doc.visibility() == DocumentVisibility.EMPLOYEE_PRIVATE) {
            return doc;
        }
        throw new NotFoundException("EMPLOYEE_DOCUMENT_NOT_FOUND", "Document not found");
    }

    private EmployeeDocument require(UUID docId) {
        return documents
                .findById(docId)
                .orElseThrow(() -> new NotFoundException("EMPLOYEE_DOCUMENT_NOT_FOUND", "Document not found"));
    }

    /**
     * Bytes before row (see {@code CompanyFileService#uploadAndList}'s ordering rationale) —
     * shared by both upload paths since only who ends up as {@code uploadedBy}/{@code visibility}
     * differs between them.
     */
    private void store(MultipartFile file, java.util.function.Function<StoredBytes, EmployeeDocument> toEntity) {
        String detectedType = validator.validate(file);
        String key = TenantContext.require() + "/" + UUID.randomUUID();
        try (InputStream in = file.getInputStream()) {
            storage.store(key, in, file.getSize(), detectedType);
        } catch (IOException e) {
            throw new ValidationException("FILE_UNREADABLE", "Could not read the uploaded file");
        }
        EmployeeDocument saved =
                documents.save(toEntity.apply(new StoredBytes(originalFilename(file), detectedType, file.getSize(), key)));
        log.info("Uploaded employee document {}", saved.requireId());
    }

    private static String originalFilename(MultipartFile file) {
        String filename = file.getOriginalFilename();
        return filename == null ? "unnamed" : filename;
    }

    private record StoredBytes(String name, String mime, long size, String key) {}
}
