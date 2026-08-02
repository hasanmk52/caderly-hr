package com.helyx.helyxhr.security;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;
import org.springframework.core.io.ClassPathResource;

/**
 * Enforces PRD §19.1: at least 10 characters, an upper-case letter, a lower-case letter, a digit,
 * and not on the common-password blocklist.
 *
 * <p>The blocklist is a local resource rather than an online breach lookup. Spring Security ships
 * {@code HaveIBeenPwnedRestApiPasswordChecker}, which is strictly better coverage, but it makes an
 * outbound call on every password set — a third-party dependency in the signup path and a privacy
 * question for an HR product. Revisit as a per-tenant opt-in.
 */
class PasswordPolicyValidator implements ConstraintValidator<ValidPassword, String> {

    private static final int MIN_LENGTH = 10;
    private static final String BLOCKLIST_RESOURCE = "security/common-passwords.txt";

    /** Loaded once; the file is small and immutable for the process lifetime. */
    private static final Set<String> BLOCKED = loadBlocklist();

    @Override
    public boolean isValid(@Nullable String password, ConstraintValidatorContext context) {
        if (password == null) {
            return false;
        }

        String failure = firstFailure(password);
        if (failure == null) {
            return true;
        }

        // Replace the generic message with the specific rule that failed, so the user is told
        // what to fix rather than being made to guess.
        context.disableDefaultConstraintViolation();
        context.buildConstraintViolationWithTemplate(failure).addConstraintViolation();
        return false;
    }

    private static @Nullable String firstFailure(String password) {
        if (password.length() < MIN_LENGTH) {
            return "Password must be at least " + MIN_LENGTH + " characters";
        }
        if (password.chars().noneMatch(Character::isUpperCase)) {
            return "Password must contain an upper-case letter";
        }
        if (password.chars().noneMatch(Character::isLowerCase)) {
            return "Password must contain a lower-case letter";
        }
        if (password.chars().noneMatch(Character::isDigit)) {
            return "Password must contain a digit";
        }
        if (BLOCKED.contains(password.toLowerCase(Locale.ROOT))) {
            return "That password is too common. Please choose another";
        }
        return null;
    }

    private static Set<String> loadBlocklist() {
        ClassPathResource resource = new ClassPathResource(BLOCKLIST_RESOURCE);
        try (InputStream in = resource.getInputStream();
                BufferedReader reader =
                        new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            return reader
                    .lines()
                    .map(String::trim)
                    .filter(line -> !line.isEmpty() && !line.startsWith("#"))
                    .map(line -> line.toLowerCase(Locale.ROOT))
                    .collect(Collectors.toUnmodifiableSet());
        } catch (IOException exception) {
            // Fail loudly at startup rather than silently accepting "password1" forever.
            throw new UncheckedIOException(
                    "Cannot read password blocklist " + BLOCKLIST_RESOURCE, exception);
        }
    }
}
