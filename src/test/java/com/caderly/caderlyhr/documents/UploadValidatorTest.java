package com.caderly.caderlyhr.documents;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.caderly.caderlyhr.common.ValidationException;
import com.caderly.caderlyhr.storage.StorageProperties;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.util.unit.DataSize;

/**
 * CLAUDE.md §6 A03: extension whitelist AND magic-byte detection AND size limit — this is where
 * the real behaviour of the pinned tika-core 3.2.3 jar gets settled empirically (ADR 0012), not
 * assumed. Every "accepted" case asserts the exact detected type, not just that validation passed,
 * so a future whitelist change that silently drifts to a wrong-but-still-allowed type is caught.
 */
class UploadValidatorTest {

    private final UploadValidator validator =
            new UploadValidator(new StorageProperties("local", new StorageProperties.Local("unused"), DataSize.ofMegabytes(25)));

    @ParameterizedTest(name = "{0} is accepted as {2}")
    @MethodSource("acceptedFiles")
    void validate_allowedType_returnsDetectedContentType(String filename, byte[] content, String expectedType)
            throws IOException {
        MockMultipartFile file = new MockMultipartFile("file", filename, null, content);

        assertThat(validator.validate(file)).isEqualTo(expectedType);
    }

    static Stream<Arguments> acceptedFiles() throws IOException {
        return Stream.of(
                Arguments.of("handbook.pdf", pdfBytes(), "application/pdf"),
                Arguments.of("logo.png", pngBytes(), "image/png"),
                Arguments.of("photo.jpg", jpegBytes(), "image/jpeg"),
                Arguments.of(
                        "contract.docx",
                        minimalOoxmlZip(),
                        "application/vnd.openxmlformats-officedocument.wordprocessingml.document"),
                Arguments.of(
                        "budget.xlsx",
                        minimalOoxmlZip(),
                        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"),
                Arguments.of(
                        "deck.pptx",
                        minimalOoxmlZip(),
                        "application/vnd.openxmlformats-officedocument.presentationml.presentation"));
    }

    @Test
    void validate_emptyFile_throwsValidationException() {
        MockMultipartFile file = new MockMultipartFile("file", "empty.pdf", null, new byte[0]);

        assertThatThrownBy(() -> validator.validate(file))
                .isInstanceOf(ValidationException.class)
                .satisfies(e -> assertThat(((ValidationException) e).errorCode()).isEqualTo("FILE_EMPTY"));
    }

    @Test
    void validate_oversizeFile_throwsValidationException() {
        UploadValidator tinyLimitValidator =
                new UploadValidator(
                        new StorageProperties("local", new StorageProperties.Local("unused"), DataSize.ofBytes(4)));
        MockMultipartFile file = new MockMultipartFile("file", "handbook.pdf", null, pdfBytesUnchecked());

        assertThatThrownBy(() -> tinyLimitValidator.validate(file))
                .isInstanceOf(ValidationException.class)
                .satisfies(e -> assertThat(((ValidationException) e).errorCode()).isEqualTo("FILE_TOO_LARGE"));
    }

    @Test
    void validate_disallowedExtension_throwsValidationException() {
        MockMultipartFile file =
                new MockMultipartFile(
                        "file", "script.js", null, "alert('hi')".getBytes(StandardCharsets.UTF_8));

        assertThatThrownBy(() -> validator.validate(file))
                .isInstanceOf(ValidationException.class)
                .satisfies(e -> assertThat(((ValidationException) e).errorCode()).isEqualTo("FILE_BAD_EXTENSION"));
    }

    @Test
    void validate_bareExecutableExtension_throwsValidationExceptionOnExtensionAlone() {
        MockMultipartFile file = new MockMultipartFile("file", "virus.exe", null, exeBytes());

        assertThatThrownBy(() -> validator.validate(file))
                .isInstanceOf(ValidationException.class)
                .satisfies(e -> assertThat(((ValidationException) e).errorCode()).isEqualTo("FILE_BAD_EXTENSION"));
    }

    /**
     * The headline security requirement (CLAUDE.md §6 A03, CURRENT_PHASE.md DoD): an extension
     * rename alone must not be enough. This file passes the extension check (.pdf is allowed) and
     * is rejected only because Tika's magic-byte detection sees the {@code MZ} executable header
     * underneath, confirming content — not the filename — decides.
     */
    @Test
    void validate_executableDisguisedWithAllowedExtension_throwsValidationExceptionOnContentType() {
        MockMultipartFile file = new MockMultipartFile("file", "payload.pdf", null, exeBytes());

        assertThatThrownBy(() -> validator.validate(file))
                .isInstanceOf(ValidationException.class)
                .satisfies(e -> assertThat(((ValidationException) e).errorCode()).isEqualTo("FILE_BAD_CONTENT_TYPE"));
    }

    private static byte[] pdfBytes() throws IOException {
        return pdfBytesUnchecked();
    }

    private static byte[] pdfBytesUnchecked() {
        return "%PDF-1.4\n1 0 obj\n<< >>\nendobj\n%%EOF".getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] pngBytes() {
        return new byte[] {
            (byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n',
            0, 0, 0, 0, 'I', 'H', 'D', 'R', 0, 0, 0, 0
        };
    }

    private static byte[] jpegBytes() {
        return new byte[] {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 0, 0, 'J', 'F', 'I', 'F'};
    }

    /** Windows PE header ({@code MZ}) — used both as a bare .exe and disguised as a .pdf. */
    private static byte[] exeBytes() {
        return new byte[] {'M', 'Z', (byte) 0x90, 0, 3, 0, 0, 0, 4, 0, 0, 0};
    }

    /**
     * A minimal ZIP whose first entry is named {@code [Content_Types].xml} — the OOXML container
     * magic Tika matches on lands its filename at byte offset 30, exactly where a ZIP local file
     * header places the first entry's name, so this is enough for Tika to recognise the file as
     * an OOXML package and let the {@code .docx}/{@code .xlsx}/{@code .pptx} extension hint
     * specialize it to the exact type.
     */
    private static byte[] minimalOoxmlZip() throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(bos)) {
            zos.putNextEntry(new ZipEntry("[Content_Types].xml"));
            zos.write("<Types/>".getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();
        }
        return bos.toByteArray();
    }
}
