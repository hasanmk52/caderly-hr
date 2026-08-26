package com.caderly.caderlyhr.documents;

import com.caderly.caderlyhr.common.ValidationException;
import com.caderly.caderlyhr.storage.StorageProperties;
import java.io.IOException;
import java.io.InputStream;
import java.util.Locale;
import java.util.Set;
import org.apache.tika.Tika;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

/**
 * Extension whitelist + Tika magic-byte detection + size limit, all three (CLAUDE.md §6 A03) —
 * generalizes {@code timeoff.PublicHolidayService#validateFileEnvelope} for the file types PRD
 * §6.7 FR-7.4 allows. Returns the Tika-detected content type rather than whatever the browser
 * declared in the multipart part: that header is attacker-controlled and unverified, so it is
 * never what gets stored as the file's {@code mime}.
 */
@Component
class UploadValidator {

    private static final Set<String> ALLOWED_EXTENSIONS =
            Set.of("pdf", "png", "jpg", "jpeg", "docx", "xlsx", "pptx");

    /**
     * Verified against the pinned tika-core 3.2.3 jar (ADR 0012): a real .docx/.xlsx/.pptx with a
     * matching extension resolves to these specific strings, not the generic
     * application/x-tika-ooxml, because Tika's filename hint specializes the zip-family magic
     * match it gets from the content. A renamed executable's magic bytes are unrelated to any of
     * these in Tika's type hierarchy, so the hint cannot pull it in.
     */
    private static final Set<String> ALLOWED_CONTENT_TYPES =
            Set.of(
                    "application/pdf",
                    "image/png",
                    "image/jpeg",
                    "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                    "application/vnd.openxmlformats-officedocument.presentationml.presentation");

    private final long maxBytes;
    private final Tika tika = new Tika();

    UploadValidator(StorageProperties storageProperties) {
        this.maxBytes = storageProperties.maxFileSize().toBytes();
    }

    /** @return the Tika-detected content type, already verified against the allowed set. */
    String validate(MultipartFile file) {
        if (file.isEmpty()) {
            throw new ValidationException("FILE_EMPTY", "The uploaded file is empty");
        }
        if (file.getSize() > maxBytes) {
            throw new ValidationException(
                    "FILE_TOO_LARGE", "The uploaded file exceeds the " + maxBytes + " byte limit");
        }
        String filename = file.getOriginalFilename();
        String extension = extensionOf(filename);
        if (extension == null || !ALLOWED_EXTENSIONS.contains(extension)) {
            throw new ValidationException(
                    "FILE_BAD_EXTENSION", "Only PDF, PNG, JPG, DOCX, XLSX, and PPTX files are accepted");
        }
        String detected;
        try (InputStream in = file.getInputStream()) {
            detected = tika.detect(in, filename);
        } catch (IOException e) {
            throw new ValidationException("FILE_UNREADABLE", "Could not read the uploaded file");
        }
        if (!ALLOWED_CONTENT_TYPES.contains(detected)) {
            throw new ValidationException(
                    "FILE_BAD_CONTENT_TYPE",
                    "The uploaded file's content doesn't match an allowed type (detected: " + detected + ")");
        }
        return detected;
    }

    private static String extensionOf(String filename) {
        if (filename == null) {
            return null;
        }
        int dot = filename.lastIndexOf('.');
        if (dot < 0 || dot == filename.length() - 1) {
            return null;
        }
        return filename.substring(dot + 1).toLowerCase(Locale.ROOT);
    }
}
