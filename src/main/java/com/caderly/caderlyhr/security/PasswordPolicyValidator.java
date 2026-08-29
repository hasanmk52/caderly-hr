package com.caderly.caderlyhr.security;

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

    /**
     * Each return value is a Bean Validation message template (curly-brace form), resolved
     * through the app's {@code messages.properties} by Hibernate Validator's interpolator — the
     * same mechanism {@link ValidPassword#message()}'s default uses (ADR 0013). {@code
     * MIN_LENGTH} is baked into {@code validation.password.min-length}'s text rather than passed
     * as a template argument: Bean Validation's own EL-based interpolation, not {@link
     * java.text.MessageFormat}, resolves these templates, so there is no positional-argument
     * mechanism to hook the constant through without a custom {@code MessageInterpolator}.
     */
    private static @Nullable String firstFailure(String password) {
        if (password.length() < MIN_LENGTH) {
            return "{validation.password.min-length}";
        }
        if (password.chars().noneMatch(Character::isUpperCase)) {
            return "{validation.password.uppercase-required}";
        }
        if (password.chars().noneMatch(Character::isLowerCase)) {
            return "{validation.password.lowercase-required}";
        }
        if (password.chars().noneMatch(Character::isDigit)) {
            return "{validation.password.digit-required}";
        }
        return null;
    }
}
