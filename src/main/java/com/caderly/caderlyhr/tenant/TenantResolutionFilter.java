package com.caderly.caderlyhr.tenant;

import com.caderly.caderlyhr.common.MdcKeys;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.slf4j.MDC;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * First filter in the chain (PRD §20.3): resolves the tenant from the Host subdomain, populates
 * {@link TenantContext}, and clears it in a finally block so no tenant ever leaks between pooled
 * request threads. Unknown tenant → 404, suspended → 503.
 *
 * <p>{@code /superadmin/**} is excluded outright: the Super Admin console is a cross-tenant realm
 * reached on the bare base domain, so there is no subdomain to resolve and a 404 is exactly what
 * this filter would otherwise return for every one of its URLs. Code in that realm runs with an
 * empty {@link TenantContext} as a result, which is why every database call reachable from {@code
 * superadmin} has to go through {@link TenantContext#runAsSystem}.
 *
 * <p>Also the request-id/tenant-id half of CLAUDE.md §6 A09's MDC correlation (ADR 0017) — it is
 * already the first filter in the chain, so a request id generated here covers everything
 * downstream, including the 404/503 denials below that never reach a resolved tenant at all.
 * {@code web.ActorMdcFilter} adds the third key, {@code actorId}, once authentication has run.
 */
public class TenantResolutionFilter extends OncePerRequestFilter {

    /** Request attribute carrying the resolved {@link TenantSummary} for the web layer. */
    public static final String TENANT_ATTRIBUTE = TenantResolutionFilter.class.getName() + ".TENANT";

    /** Echoed back so a client (or an operator reading a bug report) can correlate their own logs. */
    private static final String REQUEST_ID_HEADER = "X-Request-Id";

    private final TenantFacade tenants;
    private final String baseDomain;

    public TenantResolutionFilter(TenantFacade tenants, String baseDomain) {
        this.tenants = tenants;
        this.baseDomain = baseDomain;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        // Infrastructure endpoints and static assets are tenant-independent; /error must
        // stay reachable for the 404/503 error dispatch itself; /superadmin is a realm of its
        // own with no tenant to resolve (see this class's Javadoc). /js/ and /fonts/ join /css/
        // here for the same reason /superadmin needed it: that realm's pages are served on the
        // base domain with no tenant subdomain to resolve, and this app's own static assets
        // (caderly.js included — see its own header comment on the CSRF header it attaches to
        // every htmx request) live under these prefixes rather than /webjars/. Without this, the
        // Super Admin console's suspend/reinstate/delete actions 403 (missing CSRF header, since
        // caderly.js itself 404s here) even though the page it's on renders fine.
        String path = request.getRequestURI();
        return path.startsWith("/actuator")
                || path.startsWith("/superadmin")
                || path.startsWith("/bootui")
                || path.startsWith("/webjars/")
                || path.startsWith("/css/")
                || path.startsWith("/js/")
                || path.startsWith("/fonts/")
                || path.equals("/favicon.ico")
                || path.equals("/favicon-32x32.png")
                || path.equals("/favicon-16x16.png")
                || path.equals("/apple-touch-icon.png")
                || path.startsWith("/error");
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        String requestId = resolveRequestId(request);
        MDC.put(MdcKeys.REQUEST_ID, requestId);
        response.setHeader(REQUEST_ID_HEADER, requestId);
        try {
            String slug = extractSlug(request.getServerName());
            if (slug == null) {
                deny(response, HttpServletResponse.SC_NOT_FOUND, "Unknown tenant");
                return;
            }
            Optional<TenantSummary> tenant = tenants.bySlug(slug);
            if (tenant.isEmpty()) {
                deny(response, HttpServletResponse.SC_NOT_FOUND, "Unknown tenant");
                return;
            }
            if (tenant.get().suspended()) {
                deny(response, HttpServletResponse.SC_SERVICE_UNAVAILABLE, "Tenant suspended");
                return;
            }
            TenantContext.set(tenant.get().id());
            MDC.put(MdcKeys.TENANT_ID, tenant.get().id().toString());
            request.setAttribute(TENANT_ATTRIBUTE, tenant.get());
            filterChain.doFilter(request, response);
        } finally {
            TenantContext.clear();
            MDC.remove(MdcKeys.REQUEST_ID);
            MDC.remove(MdcKeys.TENANT_ID);
        }
    }

    /** A caller-supplied id is trusted only as a correlation label, never as anything security-relevant. */
    private static String resolveRequestId(HttpServletRequest request) {
        String supplied = request.getHeader(REQUEST_ID_HEADER);
        return supplied == null || supplied.isBlank() ? UUID.randomUUID().toString() : supplied;
    }

    // Writes the generic denial page directly (PRD §20.3) instead of sendError: an
    // error dispatch to /error would be intercepted by the security chain (403) since
    // this filter runs before it. The message is fixed and generic on purpose — it must
    // not leak whether a slug exists.
    private void deny(HttpServletResponse response, int status, String message) throws IOException {
        response.setStatus(status);
        response.setContentType("text/html;charset=UTF-8");
        response.getWriter().write("<!DOCTYPE html><html><body><h1>" + message + "</h1></body></html>");
    }

    private @Nullable String extractSlug(String host) {
        // host arrives without the port (ServletRequest#getServerName). Exactly one label
        // before the base domain is a tenant slug; anything else is not resolvable.
        if (!host.endsWith("." + baseDomain)) {
            return null;
        }
        String slug = host.substring(0, host.length() - baseDomain.length() - 1);
        if (slug.isEmpty() || slug.contains(".")) {
            return null;
        }
        return slug;
    }
}
