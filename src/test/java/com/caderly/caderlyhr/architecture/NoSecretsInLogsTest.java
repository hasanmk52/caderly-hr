package com.caderly.caderlyhr.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * CLAUDE.md §6 A09: "No secrets, tokens, or PII in logs. Verify with a grep test in CI over log
 * format." Bytecode rules cannot see this (a logger call's arguments are indistinguishable from
 * any other method call once compiled), so this reads the source — same approach as {@link
 * NoSqlConcatenationTest}.
 */
class NoSecretsInLogsTest {

    private static final Path SOURCE_ROOT = Path.of("src/main/java");

    /** Every string literal, so a log message's own wording (e.g. "Password reset...") is never
     * mistaken for a logged value — only what a {@code {}} placeholder is actually bound to
     * matters here. */
    private static final Pattern STRING_LITERAL = Pattern.compile("\"(?:[^\"\\\\]|\\\\.)*\"");

    /** One logger call, from the method name to its statement-terminating semicolon. Matched
     * against literal-stripped source, so a semicolon inside a log message's own text (there is
     * one in {@code PasswordResetService}) can no longer be mistaken for the statement's end. */
    private static final Pattern LOG_CALL =
            Pattern.compile("log\\.(?:info|warn|error|debug|trace)\\s*\\([^;]*;");

    /** An argument identifier that names a secret, whatever the log message's own wording says. */
    private static final Pattern SENSITIVE_ARGUMENT =
            Pattern.compile("(?i)\\b\\w*(?:password|secret|token|hash)\\w*\\b");

    @Test
    void noLogStatement_passesASensitivelyNamedVariableAsAnArgument() throws IOException {
        List<String> offenders = new ArrayList<>();

        try (Stream<Path> sources = Files.walk(SOURCE_ROOT)) {
            for (Path source : sources.filter(path -> path.toString().endsWith(".java")).toList()) {
                String literalsStripped = STRING_LITERAL.matcher(Files.readString(source, StandardCharsets.UTF_8)).replaceAll("");
                var calls = LOG_CALL.matcher(literalsStripped);
                while (calls.find()) {
                    if (SENSITIVE_ARGUMENT.matcher(calls.group()).find()) {
                        offenders.add(
                                SOURCE_ROOT.relativize(source) + ": " + calls.group().replaceAll("\\s+", " ").trim());
                    }
                }
            }
        }

        assertThat(offenders)
                .as(
                        "A log statement must never pass a password/secret/token/hash-named variable as an"
                                + " argument (CLAUDE.md §6 A09), whatever the message text itself says. Log the"
                                + " surrounding context (email, user id, IP) instead.")
                .isEmpty();
    }

    @Test
    void theDetector_actuallyMatchesAnOffendingLogCall() {
        assertThat(matches("log.warn(\"Reset requested for {}\", passwordHash);")).isTrue();
        assertThat(matches("log.info(\"Issued {}\", inviteTokenHash);")).isTrue();
        // The message text itself may say "password"/"token" freely — only a logged argument
        // matters, and a semicolon inside that text must not truncate the scan early.
        assertThat(matches("log.info(\"Password reset requested; responding identically\");")).isFalse();
        assertThat(matches("log.info(\"Password reset token issued for {}\", email);")).isFalse();
        assertThat(matches("log.warn(\"Account {} locked until {}\", email, user.lockedUntil());")).isFalse();
    }

    private static boolean matches(String snippet) {
        String literalsStripped = STRING_LITERAL.matcher(snippet).replaceAll("");
        var calls = LOG_CALL.matcher(literalsStripped);
        return calls.find() && SENSITIVE_ARGUMENT.matcher(calls.group()).find();
    }
}
