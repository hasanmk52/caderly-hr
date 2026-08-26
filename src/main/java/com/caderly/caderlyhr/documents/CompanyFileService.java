package com.caderly.caderlyhr.documents;

import com.caderly.caderlyhr.common.NotFoundException;
import com.caderly.caderlyhr.common.ValidationException;
import com.caderly.caderlyhr.storage.FileStorage;
import com.caderly.caderlyhr.tenant.TenantContext;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

/**
 * Tenant-wide files, Admin-uploaded and visible to every signed-in member of the tenant (PRD §6.7
 * FR-7.1). Combined write-then-read methods for {@code web.FilesController}'s mutation endpoints,
 * mirroring {@code timeoff.PublicHolidayService} (ADR 0007).
 */
@Service
public class CompanyFileService {

    private static final Logger log = LoggerFactory.getLogger(CompanyFileService.class);

    private final CompanyFileRepository companyFiles;
    private final FileStorage storage;
    private final UploadValidator validator;

    CompanyFileService(CompanyFileRepository companyFiles, FileStorage storage, UploadValidator validator) {
        this.companyFiles = companyFiles;
        this.storage = storage;
        this.validator = validator;
    }

    @Transactional(readOnly = true)
    public List<CompanyFile> listAll() {
        return companyFiles.findAllByOrderByCreatedAtDesc();
    }

    @Transactional(readOnly = true)
    public CompanyFile require(UUID id) {
        return companyFiles
                .findById(id)
                .orElseThrow(() -> new NotFoundException("COMPANY_FILE_NOT_FOUND", "File not found"));
    }

    @Transactional(readOnly = true)
    public DownloadableFile download(UUID id) {
        CompanyFile file = require(id);
        return new DownloadableFile(file.name(), file.mime(), file.sizeBytes(), storage.open(file.storageKey()));
    }

    /**
     * Bytes are written before the metadata row is saved: if the storage write fails, nothing is
     * ever persisted; if it succeeds but the save then fails, the result is an orphan file on
     * disk — harmless, and cleanable later — never a row pointing at bytes that don't exist
     * (CLAUDE.md §6a does not apply here: a local filesystem write is not an external side effect
     * needing retry/backoff, see ADR 0012).
     */
    @Transactional
    public List<CompanyFile> uploadAndList(MultipartFile file, UUID uploadedBy) {
        String detectedType = validator.validate(file);
        String key = TenantContext.require() + "/" + UUID.randomUUID();
        try (InputStream in = file.getInputStream()) {
            storage.store(key, in, file.getSize(), detectedType);
        } catch (IOException e) {
            throw new ValidationException("FILE_UNREADABLE", "Could not read the uploaded file");
        }
        CompanyFile saved =
                companyFiles.save(
                        CompanyFile.create(
                                originalFilename(file), detectedType, file.getSize(), key, uploadedBy));
        log.info("Uploaded company file {}", saved.requireId());
        return listAll();
    }

    /**
     * Deletes the row and the bytes in the same transaction, row first: if the storage delete
     * then fails, the whole transaction — including the row delete — rolls back, so the file and
     * its row either both go or neither does. The only way to end up with an orphan file is the
     * database commit itself failing after this method returns, the same residual risk any commit
     * carries.
     */
    @Transactional
    public List<CompanyFile> deleteAndList(UUID id) {
        CompanyFile file = require(id);
        companyFiles.delete(file);
        storage.delete(file.storageKey());
        log.info("Deleted company file {}", id);
        return listAll();
    }

    private static String originalFilename(MultipartFile file) {
        String filename = file.getOriginalFilename();
        return filename == null ? "unnamed" : filename;
    }
}
