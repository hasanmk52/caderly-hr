package com.helyx.helyxhr.web;

import com.helyx.helyxhr.common.HelyxException;
import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.servlet.ModelAndView;

/**
 * Maps {@link HelyxException} to RFC 7807 Problem Details for API clients (PRD §23.3, CLAUDE.md
 * §7), and to a rendered error page for browsers.
 *
 * <p>The content negotiation matters because Helyx is server-rendered: returning JSON to a form
 * POST would replace the page with a blob of text. Anything the user is expected to correct —
 * a bad password, an expired token — is handled inline by the controller and never reaches here;
 * this is the backstop for genuinely exceptional cases.
 */
@ControllerAdvice
class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);
    private static final String ERROR_TYPE_BASE = "https://helyx.app/errors/";

    @ExceptionHandler(HelyxException.class)
    Object handleHelyxException(HelyxException exception, HttpServletRequest request) {
        // warn, not error: these are expected, handled failures (CLAUDE.md §7 logging levels).
        log.warn(
                "Request to {} failed with {}: {}",
                request.getRequestURI(),
                exception.errorCode(),
                exception.getMessage());

        if (prefersHtml(request)) {
            ModelAndView modelAndView = new ModelAndView("error/error");
            modelAndView.setStatus(exception.status());
            modelAndView.addObject("status", exception.status().value());
            modelAndView.addObject("title", exception.status().getReasonPhrase());
            modelAndView.addObject("detail", exception.getMessage());
            return modelAndView;
        }

        ProblemDetail problem =
                ProblemDetail.forStatusAndDetail(exception.status(), exception.getMessage());
        problem.setType(URI.create(ERROR_TYPE_BASE + slug(exception.errorCode())));
        problem.setTitle(exception.status().getReasonPhrase());
        problem.setInstance(URI.create(request.getRequestURI()));
        problem.setProperty("errorCode", exception.errorCode());
        return ResponseEntity.status(exception.status()).body(problem);
    }

    /**
     * Treats a request as a browser navigation unless it explicitly asks for JSON. Checking for an
     * html Accept header rather than a JSON one keeps htmx requests — which send {@code Accept:
     * text/html} — on the HTML branch, where a swapped-in error fragment is what the page expects.
     */
    private static boolean prefersHtml(HttpServletRequest request) {
        String accept = request.getHeader("Accept");
        return accept != null && accept.contains(MediaType.TEXT_HTML_VALUE);
    }

    /** {@code INVITE_TOKEN_INVALID} to {@code invite-token-invalid}, per PRD §23.3's type URIs. */
    private static String slug(String errorCode) {
        return errorCode.toLowerCase(Locale.ROOT).replace('_', '-');
    }
}
