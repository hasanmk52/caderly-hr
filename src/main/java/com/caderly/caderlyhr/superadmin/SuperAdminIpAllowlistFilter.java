package com.caderly.caderlyhr.superadmin;

import com.caderly.caderlyhr.common.ClientIpResolver;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.web.util.matcher.IpAddressMatcher;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Network-level gate in front of the Super Admin console (PRD FR-1.8): a request whose client
 * address is outside {@code caderly.superadmin.ip-allowlist} is refused before it can reach the
 * login form, spend a rate-limit token, or cause a single database lookup.
 *
 * <p><strong>Fail closed.</strong> An empty allowlist denies everyone — it is not read as "no
 * restriction configured". That is the whole point of a second factor that is not a password: a
 * deployment that forgets to set the variable gets a console nobody can reach, which is loud and
 * recoverable, rather than a console the whole internet can reach, which is neither.
 *
 * <p>Client address resolution goes through {@link ClientIpResolver}, the same helper {@code
 * security.RateLimitFilter} and the login lockout use, so all three agree on what "the client" is
 * behind the reverse proxy — an allowlist keyed on a different address than the rate limiter would
 * be a quiet bypass of one or the other.
 */
public class SuperAdminIpAllowlistFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(SuperAdminIpAllowlistFilter.class);

    /** Matchers are parsed once: {@code IpAddressMatcher} construction is not free per request. */
    private final List<IpAddressMatcher> allowed;

    public SuperAdminIpAllowlistFilter(List<String> allowedCidrs) {
        this.allowed = allowedCidrs.stream().map(IpAddressMatcher::new).toList();
    }

    /** Only this realm is gated; every tenant URL is unaffected by the operator allowlist. */
    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith("/superadmin");
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        String clientIp = ClientIpResolver.resolve(request);
        if (allowed.stream().anyMatch(matcher -> matcher.matches(clientIp))) {
            filterChain.doFilter(request, response);
            return;
        }
        // Worth a warning even when the allowlist is simply unset: someone is knocking on the
        // operator console, and an operator locked out by a misconfigured proxy needs to see why.
        log.warn("Blocked Super Admin console request from non-allowlisted address {}", clientIp);
        response.sendError(HttpServletResponse.SC_FORBIDDEN);
    }
}
