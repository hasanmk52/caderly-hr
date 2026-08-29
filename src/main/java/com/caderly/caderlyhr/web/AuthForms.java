package com.caderly.caderlyhr.web;

import com.caderly.caderlyhr.security.ValidPassword;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

/**
 * Form-backing records for the auth pages (CLAUDE.md §7: DTOs are records).
 *
 * <p>These live in {@code web} rather than {@code identity} on purpose. They carry {@link
 * ValidPassword} from {@code security}, and {@code security} already depends on {@code identity} —
 * putting them in {@code identity} would close a package cycle.
 */
final class AuthForms {

    private AuthForms() {}

    record ForgotPassword(
            @NotBlank(message = "{validation.forgot-password-form.email.required}")
                    @Email(message = "{validation.forgot-password-form.email.invalid}")
                    String email) {}

    /**
     * Password confirmation is checked in the controller rather than by a class-level constraint.
     * A cross-field validator would report the mismatch against the whole object, and the field
     * error has to land on the confirmation input for the UI to mark it (UI Guidelines §6).
     */
    record SetPassword(
            @NotBlank(message = "{validation.set-password-form.token.required}") String token,
            @ValidPassword String password,
            String confirmPassword) {

        boolean passwordsMatch() {
            return password != null && password.equals(confirmPassword);
        }
    }
}
