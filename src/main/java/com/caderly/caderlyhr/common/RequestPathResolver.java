package com.caderly.caderlyhr.common;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.server.PathContainer;

/**
 * Resolves the path a filter should make a security decision on, for the same reason {@link
 * ClientIpResolver} exists: a defense keyed on a different string than the one Spring Security
 * matches on is not a defense, it is a bypass waiting to be found.
 *
 * <p>{@code HttpServletRequest.getRequestURI()} is specified to return the <em>undecoded</em> URI,
 * while Spring Security's {@code PathPatternRequestMatcher} — what backs {@code securityMatcher}
 * and {@code loginProcessingUrl} — matches against the decoded path. A plain {@code
 * getRequestURI().startsWith("/superadmin")} check therefore misses {@code /%73uperadmin/login},
 * which the security chain still routes to the Super Admin login filter — letting a
 * percent-encoded request bypass both the IP allowlist and the rate limiter (ADR 0019).
 *
 * <p>Decoding goes through {@link PathContainer} rather than {@code URLDecoder} so it reproduces
 * the chain's own semantics exactly — per <em>segment</em>, so {@code %2F} decodes to a character
 * inside a segment instead of creating a new boundary, and matrix parameters ({@code
 * /superadmin;x=y/login}) are stripped the way {@code PathPattern} strips them. Decoding is
 * single-pass for the same reason: decoding twice would make this helper disagree with the chain
 * in the other direction.
 */
public final class RequestPathResolver {

    private RequestPathResolver() {}

    /**
     * The decoded path of {@code request}, or its raw URI if that URI carries a malformed escape.
     *
     * <p>The fallback is safe in the direction that matters: a malformed sequence the chain cannot
     * decode is one the chain rejects rather than routes, so returning the raw URI here cannot let
     * a request through that the chain would have treated as a Super Admin URL.
     */
    public static String decodedPath(HttpServletRequest request) {
        String uri = request.getRequestURI();
        try {
            StringBuilder decoded = new StringBuilder(uri.length());
            for (PathContainer.Element element : PathContainer.parsePath(uri).elements()) {
                decoded.append(
                        element instanceof PathContainer.PathSegment segment
                                ? segment.valueToMatch()
                                : element.value());
            }
            return decoded.toString();
        } catch (IllegalArgumentException malformedEscape) {
            return uri;
        }
    }
}
