package com.caderly.caderlyhr.tenant;

import com.caderly.caderlyhr.common.ValidationException;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.Locale;
import java.util.Set;
import org.apache.tika.Tika;
import org.springframework.stereotype.Component;

/**
 * Extension whitelist + Tika magic-byte detection + size limit for a tenant logo (CLAUDE.md §6
 * A03). PNG and JPEG only: the logo is served same-origin from the public {@code /tenant-logo}
 * route, and an SVG there could carry script. As in {@code documents.UploadValidator}, the
 * Tika-detected type is what gets stored — the browser-declared one is attacker-controlled.
 */
@Component
class TenantLogoValidator {

    static final int MAX_BYTES = 512 * 1024;

    private static final Set<String> ALLOWED_EXTENSIONS = Set.of("png", "jpg", "jpeg");
    private static final Set<String> ALLOWED_CONTENT_TYPES = Set.of("image/png", "image/jpeg");

    private final Tika tika = new Tika();

    /** @return the Tika-detected content type, already verified against the allowed set. */
    String validate(String filename, byte[] content) {
        if (content.length == 0) {
            throw new ValidationException("LOGO_EMPTY", "The uploaded logo is empty");
        }
        if (content.length > MAX_BYTES) {
            throw new ValidationException("LOGO_TOO_LARGE", "The logo must be 512 KB or smaller");
        }
        String extension = extensionOf(filename);
        if (extension == null || !ALLOWED_EXTENSIONS.contains(extension)) {
            throw new ValidationException("LOGO_BAD_EXTENSION", "The logo must be a PNG or JPEG image");
        }
        String detected;
        try {
            detected = tika.detect(new ByteArrayInputStream(content), filename);
        } catch (IOException e) {
            throw new ValidationException("LOGO_UNREADABLE", "Could not read the uploaded logo");
        }
        if (!ALLOWED_CONTENT_TYPES.contains(detected)) {
            throw new ValidationException("LOGO_BAD_CONTENT_TYPE", "The logo's content is not a PNG or JPEG image");
        }
        return detected;
    }

    private static String extensionOf(String filename) {
        int dot = filename.lastIndexOf('.');
        if (dot < 0 || dot == filename.length() - 1) {
            return null;
        }
        return filename.substring(dot + 1).toLowerCase(Locale.ROOT);
    }
}
