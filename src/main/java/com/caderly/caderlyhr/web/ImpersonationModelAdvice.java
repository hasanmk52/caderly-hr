package com.caderly.caderlyhr.web;

import jakarta.servlet.http.HttpServletRequest;
import org.jspecify.annotations.Nullable;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;

/**
 * Exposes the current support session, if any, to every Thymeleaf view as {@code impersonation},
 * so {@code layout.html} can render the banner that says whose account is being driven and by whom
 * (PRD FR-1.8).
 *
 * <p>A {@code @ControllerAdvice} {@code @ModelAttribute}, matching {@link TenantModelAdvice}
 * exactly — this codebase's one existing mechanism for cross-cutting template data. The
 * alternatives were worse for the same reason in each case: reading the session from the template
 * ({@code #httpServletRequest.session}) puts a servlet API call in the markup and silently creates
 * a session on pages that had none, and a {@code HandlerInterceptor} would be a second, parallel
 * mechanism for something the first already does.
 *
 * <p>Null on an ordinary login, which is what the banner's {@code th:if} keys on.
 */
@ControllerAdvice
class ImpersonationModelAdvice {

    @ModelAttribute("impersonation")
    @Nullable ImpersonationSession impersonation(HttpServletRequest request) {
        return ImpersonationSession.of(request);
    }
}
