package com.caderly.caderlyhr.web;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.jspecify.annotations.Nullable;

/** Form records for the public site (ADR 0022). */
final class SiteForms {

    private SiteForms() {}

    /**
     * The sign-up form. {@code website} is the honeypot: hidden from people, so anything in it
     * came from a bot (ADR 0022 decision E). Address rules beyond "present and short enough" are
     * {@code tenant.SlugPolicy}'s, applied by the service so the message is the same everywhere.
     */
    record Signup(
            @NotBlank(message = "{validation.signup-form.company-name.required}")
                    @Size(max = 200, message = "{validation.signup-form.company-name.too-long}")
                    String companyName,
            @NotBlank(message = "{validation.signup-form.slug.required}")
                    @Size(max = 50, message = "{validation.signup-form.slug.too-long}")
                    String slug,
            @NotBlank(message = "{validation.signup-form.admin-name.required}")
                    @Size(max = 200, message = "{validation.signup-form.admin-name.too-long}")
                    String adminName,
            @NotBlank(message = "{validation.signup-form.admin-email.required}")
                    @Email(message = "{validation.signup-form.admin-email.invalid}")
                    @Size(max = 255, message = "{validation.signup-form.admin-email.too-long}")
                    String adminEmail,
            @NotBlank(message = "{validation.signup-form.timezone.required}")
                    @Size(max = 50, message = "{validation.signup-form.timezone.required}")
                    String timezone,
            @NotBlank(message = "{validation.signup-form.weekend.required}")
                    @Pattern(regexp = "SAT_SUN|FRI_SAT|FRI|SUN", message = "{validation.signup-form.weekend.required}")
                    String weekend,
            @AssertTrue(message = "{validation.signup-form.accept-terms.required}") boolean acceptTerms,
            @Nullable String website) {

        static Signup blank() {
            return new Signup("", "", "", "", "UTC", "SAT_SUN", false, "");
        }

        boolean isBot() {
            return website != null && !website.isBlank();
        }
    }
}
