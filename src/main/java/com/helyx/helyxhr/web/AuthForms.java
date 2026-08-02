package com.helyx.helyxhr.web;

import com.helyx.helyxhr.identity.Role;
import com.helyx.helyxhr.security.ValidPassword;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import java.util.Set;

/**
 * Form-backing records for the auth pages (CLAUDE.md §7: DTOs are records).
 *
 * <p>These live in {@code web} rather than {@code identity} on purpose. They carry {@link
 * ValidPassword} from {@code security}, and {@code security} already depends on {@code identity} —
 * putting them in {@code identity} would close a package cycle.
 */
final class AuthForms {

    private AuthForms() {}

    record ForgotPassword(@NotBlank @Email String email) {}

    /**
     * Password confirmation is checked in the controller rather than by a class-level constraint.
     * A cross-field validator would report the mismatch against the whole object, and the field
     * error has to land on the confirmation input for the UI to mark it (UI Guidelines §6).
     */
    record SetPassword(@NotBlank String token, @ValidPassword String password, String confirmPassword) {

        boolean passwordsMatch() {
            return password != null && password.equals(confirmPassword);
        }
    }

    record InviteUser(@NotBlank @Email String email, @NotEmpty Set<Role> roles) {}
}
