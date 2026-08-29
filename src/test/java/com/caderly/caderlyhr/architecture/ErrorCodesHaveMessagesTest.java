package com.caderly.caderlyhr.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Properties;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * ADR 0013: every {@code CaderlyException} {@code errorCode} should resolve through {@code
 * messages.properties} — a missing key silently falls back to the throw site's own message
 * (safe, but not actually externalized). {@link #INTENTIONALLY_UNKEYED} is the reasoned
 * exception list: an errorCode whose message embeds a runtime value ({@code
 * GlobalExceptionHandler} resolves with no {@code MessageSource} args today, so a static
 * override would silently drop the detail) or, for {@code EMPLOYEE_NOT_FOUND}, a genuine
 * two-different-messages conflict across throw sites (see {@code messages.properties}' own
 * comment). Adding a new errorCode means either giving it a key or adding it here with a reason
 * — never silently falling through unnoticed.
 *
 * <p>Scope limit, same spirit as {@link NoSqlConcatenationTest}: the regex only sees a literal
 * string passed directly to {@code new XException(...)}. {@code
 * EmployeeService#requireOwnedBy}'s three callers pass their errorCode as an argument to that
 * helper, not to a constructor directly, so {@code EMERGENCY_CONTACT_NOT_FOUND}, {@code
 * GOVERNMENT_ID_NOT_FOUND}, and {@code EDUCATION_NOT_FOUND} aren't scanned by this test even
 * though all three already have {@code messages.properties} entries (verified by hand).
 */
class ErrorCodesHaveMessagesTest {

    private static final Path SOURCE_ROOT = Path.of("src/main/java");
    private static final Path MESSAGES_FILE = Path.of("src/main/resources/messages.properties");

    private static final Pattern ERROR_CODE_LITERAL =
            Pattern.compile(
                    "new (?:NotFoundException|ConflictException|ValidationException)\\(\\s*\"([A-Z0-9_]+)\"");

    private static final Set<String> INTENTIONALLY_UNKEYED =
            Set.of(
                    "DIVISION_NAME_TAKEN",
                    "DEPARTMENT_NAME_TAKEN",
                    "LEAVE_TYPE_NAME_TAKEN",
                    "LEAVE_BALANCE_BELOW_USED",
                    "LEAVE_BALANCE_NOT_FOUND",
                    "LEAVE_REQUEST_ILLEGAL_TRANSITION",
                    "PUBLIC_HOLIDAY_DUPLICATE",
                    "HOLIDAY_CSV_TOO_LARGE",
                    "HOLIDAY_CSV_BAD_CONTENT_TYPE",
                    "FILE_TOO_LARGE",
                    "FILE_BAD_CONTENT_TYPE",
                    "EMPLOYEE_NOT_FOUND");

    @Test
    void everyLiteralErrorCode_hasAMessagesEntry_orIsOnTheIntentionalExclusionList() throws IOException {
        Set<String> errorCodes = new LinkedHashSet<>();
        try (Stream<Path> sources = Files.walk(SOURCE_ROOT)) {
            for (Path source : sources.filter(path -> path.toString().endsWith(".java")).toList()) {
                String content = Files.readString(source, StandardCharsets.UTF_8);
                Matcher matcher = ERROR_CODE_LITERAL.matcher(content);
                while (matcher.find()) {
                    errorCodes.add(matcher.group(1));
                }
            }
        }
        assertThat(errorCodes).as("regex should still find codes seen at audit time").isNotEmpty();

        Properties messages = new Properties();
        try (BufferedReader in = Files.newBufferedReader(MESSAGES_FILE, StandardCharsets.UTF_8)) {
            messages.load(in);
        }

        Set<String> unaccountedFor =
                errorCodes.stream()
                        .filter(code -> !INTENTIONALLY_UNKEYED.contains(code))
                        .filter(code -> !messages.containsKey(errorKey(code)))
                        .collect(Collectors.toCollection(LinkedHashSet::new));

        assertThat(unaccountedFor)
                .as(
                        "Every errorCode must either have a messages.properties entry or be added to"
                                + " INTENTIONALLY_UNKEYED with a reason (ADR 0013) — never silently fall through.")
                .isEmpty();
    }

    @Test
    void theDetector_actuallyMatchesALiteralErrorCodeConstruction() {
        assertThat(
                        ERROR_CODE_LITERAL
                                .matcher(
                                        "throw new NotFoundException(\"DEPARTMENT_NOT_FOUND\", \"Department not found\");")
                                .find())
                .isTrue();
        assertThat(
                        ERROR_CODE_LITERAL
                                .matcher(
                                        "new ConflictException(\n        \"DIVISION_NAME_TAKEN\", \"A division named \\\"\" + name)")
                                .find())
                .isTrue();
        assertThat(ERROR_CODE_LITERAL.matcher("new NotFoundException(errorCode, \"Not found\")").find())
                .isFalse();
    }

    private static String errorKey(String errorCode) {
        return "error." + errorCode.toLowerCase(Locale.ROOT).replace('_', '-');
    }
}
