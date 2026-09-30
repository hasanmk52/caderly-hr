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

    /**
     * Where a Super Admin's impersonation ticket is redeemed (PRD FR-1.8). Here for the same
     * reason as the path above — {@code SecurityConfig} permits it, {@code
     * web.ImpersonationController} serves it, and {@code superadmin} builds the redirect URL.
     *
     * <p><strong>Deliberately not {@code /superadmin-impersonate}.</strong> Both {@code
     * tenant.TenantResolutionFilter} and {@code superadmin.SuperAdminIpAllowlistFilter} decide
     * what belongs to the operator realm with {@code path.startsWith("/superadmin")}, not a
     * {@code /superadmin/} prefix. A path spelled that way would therefore skip tenant resolution
     * — leaving this endpoint with no {@code TenantContext} to check the ticket against or to
     * load the target Admin in — while also putting a tenant-subdomain URL behind the operator IP
     * allowlist. This is a tenant URL and is named like one.
     */
    public static final String IMPERSONATE_PATH = "/impersonate";

    /** Ends a support session and returns the browser to this realm's login page (PRD FR-1.8). */
    public static final String END_IMPERSONATION_PATH = "/end-impersonation";

    private SecurityPaths() {}
}
