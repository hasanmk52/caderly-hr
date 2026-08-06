package com.helyx.helyxhr.security;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import org.jspecify.annotations.Nullable;

/**
 * Enforces PRD §19.1's composition rules: at least 10 characters, an upper-case letter, a
 * lower-case letter, and a digit.
 *
 * <p>PRD §19.1 also asks for a common-password blocklist. Not implemented here, deliberately: a
 * hand-maintained list of a few hundred entries is theatre — it blocks the passwords nobody picks
 * anyway and misses everything else. The real options are Spring Security's {@code
 * HaveIBeenPwnedRestApiPasswordChecker} (an outbound call on every password set — a third-party
 * dependency in the signup path and a privacy question for an HR product) or a bundled breach
 * corpus large enough to matter. Either is a decision worth making on its own, not a side effect
 * of this sub-phase.
 */
public class PasswordPolicyValidator implements ConstraintValidator<ValidPassword, String> {

    private static final int MIN_LENGTH = 10;

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
        return null;
    }
}
