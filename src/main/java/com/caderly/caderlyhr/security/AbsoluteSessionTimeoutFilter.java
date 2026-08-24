package com.caderly.caderlyhr.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Caps session lifetime at 24 hours regardless of activity (PRD §19.1).
 *
 * <p>This exists because {@code server.servlet.session.timeout} is an <em>idle</em> timeout only:
 * a session kept warm by activity — or by a background poll — never expires under it. PRD §19.1
 * asks for both an 8 h idle limit and a 24 h absolute one, and the container only provides the
 * first. Worth stating plainly, because the property looks like it covers both.
 */
public class AbsoluteSessionTimeoutFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(AbsoluteSessionTimeoutFilter.class);

    private final Duration maxSessionAge;
    private final Clock clock;

    public AbsoluteSessionTimeoutFilter(Duration maxSessionAge, Clock clock) {
        this.maxSessionAge = maxSessionAge;
        this.clock = clock;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {

        HttpSession session = request.getSession(false);
        if (session != null) {
            long ageMillis = clock.millis() - session.getCreationTime();
            if (ageMillis > maxSessionAge.toMillis()) {
                log.info("Session {} exceeded the absolute lifetime cap; invalidating", session.getId());
                session.invalidate();
                SecurityContextHolder.clearContext();
                response.sendRedirect(request.getContextPath() + "/login?expired");
                return;
            }
        }
        chain.doFilter(request, response);
    }
}
