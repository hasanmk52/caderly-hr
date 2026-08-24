package com.caderly.caderlyhr.security;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Duration;
import java.util.Locale;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpMethod;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Throttles the two endpoints an attacker would hammer (PRD §19.7): login at 10/min per IP, and
 * password-reset requests at 3/hour per email address.
 *
 * <p>The login limit is also what keeps the per-user lockout honest. Lockout is keyed on the user
 * alone in this phase (ADR 0006 decision B), so this filter is the only thing bounding a single
 * IP spraying one password across many accounts.
 *
 * <p>In-JVM buckets, matching the in-JVM session store. Both become shared state at the same
 * moment — when the deployment goes multi-instance — and PRD §19.7 already names Redis for then.
 */
public class RateLimitFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(RateLimitFilter.class);

    /** PRD §19.7: 10 login attempts per minute per IP, 3 reset requests per hour per address. */
    private static final Bandwidth LOGIN_LIMIT = perWindow(10, Duration.ofMinutes(1));

    private static final Bandwidth RESET_LIMIT = perWindow(3, Duration.ofHours(1));

    private final String loginPath;
    private final String forgotPasswordPath;

    /**
     * Buckets expire well after their refill window so an idle key cannot pin memory, while an
     * actively-attacking key keeps its (by then empty) bucket alive. Bounded size caps the damage
     * from a spray across many distinct keys.
     */
    private final Cache<String, Bucket> buckets =
            Caffeine.newBuilder().maximumSize(100_000).expireAfterAccess(Duration.ofHours(2)).build();

    public RateLimitFilter(String loginPath, String forgotPasswordPath) {
        this.loginPath = loginPath;
        this.forgotPasswordPath = forgotPasswordPath;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {

        if (!HttpMethod.POST.matches(request.getMethod())) {
            chain.doFilter(request, response);
            return;
        }

        String path = request.getRequestURI();
        if (!allowed(request, path)) {
            // Redirect rather than a bare 429: these are browser form posts, and the user needs
            // the page back with an explanation. ?rateLimited is what the template renders on.
            response.sendRedirect(request.getContextPath() + path + "?rateLimited");
            return;
        }
        chain.doFilter(request, response);
    }

    /** Consumes a token for whichever limit covers this request; {@code true} if one was free. */
    private boolean allowed(HttpServletRequest request, String path) {
        if (loginPath.equals(path)) {
            return consume("login:" + clientIp(request), LOGIN_LIMIT);
        }
        if (forgotPasswordPath.equals(path)) {
            String email = request.getParameter("email");
            // No address to key on: let it through, and the form's own validation rejects it.
            if (email == null || email.isBlank()) {
                return true;
            }
            return consume("reset:" + email.trim().toLowerCase(Locale.ROOT), RESET_LIMIT);
        }
        return true;
    }

    private boolean consume(String key, Bandwidth limit) {
        Bucket bucket = buckets.get(key, ignored -> Bucket.builder().addLimit(limit).build());
        if (bucket.tryConsume(1)) {
            return true;
        }
        // Log the bucket key, never the submitted credentials.
        log.warn("Rate limit exceeded for {}", key);
        return false;
    }

    private static Bandwidth perWindow(long capacity, Duration window) {
        return Bandwidth.builder().capacity(capacity).refillGreedy(capacity, window).build();
    }

    /**
     * Trusts {@code X-Forwarded-For} only for its first hop, and only because Caderly always runs
     * behind a reverse proxy that sets it (PRD §27: Caddy). A direct-to-app deployment would make
     * this header attacker-controlled and the limit trivially bypassable.
     */
    private static String clientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded == null || forwarded.isBlank()) {
            return request.getRemoteAddr();
        }
        int comma = forwarded.indexOf(',');
        return (comma < 0 ? forwarded : forwarded.substring(0, comma)).trim();
    }

    /**
     * Drops accumulated buckets, for one key or all of them.
     *
     * <p>Public because tests need a clean slate between cases without rebuilding the application
     * context — several login attempts in one test class would otherwise trip the limit and turn
     * unrelated assertions red. Also the natural hook for an operator who needs to lift a limit
     * on someone locked out by a misconfigured proxy.
     */
    public void clearBuckets(@Nullable String key) {
        if (key == null) {
            buckets.invalidateAll();
        } else {
            buckets.invalidate(key);
        }
    }
}
