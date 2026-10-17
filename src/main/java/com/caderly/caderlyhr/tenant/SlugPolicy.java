package com.caderly.caderlyhr.tenant;

import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Which workspace addresses may exist (ADR 0022 decision D). Shared by self-serve sign-up and the
 * Super Admin console so an address refused in one place is refused in the other.
 *
 * <p>Format is the console's original rule: lowercase letters, digits and inner hyphens, at most
 * 50 characters ({@code tenant.slug} is {@code varchar(50)}). Sign-up additionally requires three
 * characters; the console keeps allowing shorter slugs because an operator may need one.
 */
public final class SlugPolicy {

    /** Kept in step with the {@code @Pattern} on the console's create form. */
    public static final String FORMAT_REGEX = "^[a-z0-9]([a-z0-9-]{0,48}[a-z0-9])?$";

    public static final int SELF_SERVE_MIN_LENGTH = 3;

    private static final Pattern FORMAT = Pattern.compile(FORMAT_REGEX);

    /**
     * Hosts that belong to Caderly itself, or that read as if they do. {@code www} is also what
     * {@link TenantResolutionFilter} treats as the public site, so a tenant called {@code www}
     * could never be reached.
     */
    private static final Set<String> RESERVED =
            Set.of(
                    "www", "admin", "administrator", "api", "app", "apps", "mail", "email", "smtp",
                    "superadmin", "caderly", "support", "help", "status", "login", "signin", "signup",
                    "register", "account", "accounts", "billing", "docs", "blog", "static", "assets",
                    "cdn", "img", "files", "security", "legal", "privacy", "terms", "dashboard",
                    "root", "system", "test", "dev", "staging", "demo", "localhost");

    public enum Problem {
        REQUIRED,
        TOO_SHORT,
        INVALID_FORMAT,
        RESERVED
    }

    private SlugPolicy() {}

    /** Lower-cased and trimmed, the only form a slug is ever compared or stored in. */
    public static String normalise(String raw) {
        return raw.trim().toLowerCase(Locale.ROOT);
    }

    public static boolean isReserved(String slug) {
        return RESERVED.contains(normalise(slug));
    }

    /** The first rule a self-serve address breaks, or empty if it is acceptable. */
    public static Optional<Problem> checkSelfServe(String raw) {
        String slug = normalise(raw);
        if (slug.isEmpty()) {
            return Optional.of(Problem.REQUIRED);
        }
        if (slug.length() < SELF_SERVE_MIN_LENGTH) {
            return Optional.of(Problem.TOO_SHORT);
        }
        if (!FORMAT.matcher(slug).matches()) {
            return Optional.of(Problem.INVALID_FORMAT);
        }
        if (RESERVED.contains(slug)) {
            return Optional.of(Problem.RESERVED);
        }
        return Optional.empty();
    }
}
