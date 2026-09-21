package com.caderly.caderlyhr.security;

/**
 * The authentication entry points, in one place because more than one package needs them: {@code
 * SecurityConfig} builds the tenant realm's form login around the first two, {@code
 * superadmin.SuperAdminSecurityConfig} builds the Super Admin realm's around the third, and {@code
 * RateLimitFilter} throttles all three.
 *
 * <p>The Super Admin path lives here rather than in {@code superadmin} — where it would otherwise
 * belong — because {@code security} would then have to depend on {@code superadmin} to rate-limit
 * it, while {@code superadmin} already depends on {@code security} for {@link SecurityHeaders}.
 * That is a package cycle, which {@code ArchitectureTest.packages_haveNoCycles} rejects.
 */
public final class SecurityPaths {

    public static final String LOGIN_PATH = "/login";
    public static final String FORGOT_PASSWORD_PATH = "/forgot-password";
    public static final String SUPER_ADMIN_LOGIN_PATH = "/superadmin/login";

    private SecurityPaths() {}
}
