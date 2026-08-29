package com.caderly.caderlyhr.web;

import com.caderly.caderlyhr.common.CaderlyException;
import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.MessageSource;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.servlet.ModelAndView;
import org.springframework.web.servlet.support.RequestContextUtils;

/**
 * Maps {@link CaderlyException} to RFC 7807 Problem Details for API clients (PRD §23.3, CLAUDE.md
 * §7), and to a rendered error page for browsers.
 *
 * <p>The content negotiation matters because Caderly is server-rendered: returning JSON to a form
 * POST would replace the page with a blob of text. Anything the user is expected to correct —
 * a bad password, an expired token — is handled inline by the controller and never reaches here;
 * this is the backstop for genuinely exceptional cases.
 */
@ControllerAdvice
class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);
    private static final String ERROR_TYPE_BASE = "https://caderly.app/errors/";

    private final MessageSource messageSource;

    GlobalExceptionHandler(MessageSource messageSource) {
        this.messageSource = messageSource;
    }

    @ExceptionHandler(CaderlyException.class)
    Object handleCaderlyException(CaderlyException exception, HttpServletRequest request) {
        // warn, not error: these are expected, handled failures (CLAUDE.md §7 logging levels).
        log.warn(
                "Request to {} failed with {}: {}",
                request.getRequestURI(),
                exception.errorCode(),
                exception.getMessage());

        String detail = resolve("error." + slug(exception.errorCode()), exception.getMessage(), request);

        if (prefersHtml(request)) {
            ModelAndView modelAndView = new ModelAndView("error/error");
            modelAndView.setStatus(exception.status());
            modelAndView.addObject("status", exception.status().value());
            modelAndView.addObject("title", exception.status().getReasonPhrase());
            modelAndView.addObject("detail", detail);
            return modelAndView;
        }

        ProblemDetail problem = ProblemDetail.forStatusAndDetail(exception.status(), detail);
        problem.setType(URI.create(ERROR_TYPE_BASE + slug(exception.errorCode())));
        problem.setTitle(exception.status().getReasonPhrase());
        problem.setInstance(URI.create(request.getRequestURI()));
        problem.setProperty("errorCode", exception.errorCode());
        return ResponseEntity.status(exception.status()).body(problem);
    }

    /**
     * Backstop for a client that bypasses {@code documents.UploadValidator}'s own, friendlier
     * size check (CLAUDE.md §6a's servlet limit sits above the app limit precisely so a normal
     * oversize upload never reaches here — see ADR 0012). Without this handler the exception
     * would fall through to Boot's default error dispatch as a plain 500, not the 413 CLAUDE.md
     * §6 A03 calls for.
     */
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    Object handleMaxUploadSizeExceeded(MaxUploadSizeExceededException exception, HttpServletRequest request) {
        log.warn("Request to {} exceeded the multipart size limit", request.getRequestURI());
        String detail = resolve("error.upload-too-large", "The uploaded file is too large.", request);

        if (prefersHtml(request)) {
            ModelAndView modelAndView = new ModelAndView("error/error");
            modelAndView.setStatus(HttpStatus.CONTENT_TOO_LARGE);
            modelAndView.addObject("status", HttpStatus.CONTENT_TOO_LARGE.value());
            modelAndView.addObject("title", HttpStatus.CONTENT_TOO_LARGE.getReasonPhrase());
            modelAndView.addObject("detail", detail);
            return modelAndView;
        }

        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.CONTENT_TOO_LARGE, detail);
        problem.setTitle(HttpStatus.CONTENT_TOO_LARGE.getReasonPhrase());
        problem.setInstance(URI.create(request.getRequestURI()));
        return ResponseEntity.status(HttpStatus.CONTENT_TOO_LARGE).body(problem);
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

    /**
     * Resolves a {@code messages.properties} key (ADR 0013), falling back to {@code defaultValue}
     * when no entry exists yet — the existing throw sites' own message strings serve as that
     * fallback, so populating the properties file is a mechanical audit, not a source-code edit.
     *
     * <p>Uses the request's resolved locale ({@link RequestContextUtils#getLocale}), not {@link
     * org.springframework.context.i18n.LocaleContextHolder#getLocale()} — the latter falls back to
     * the JVM's default locale whenever no request-scoped {@code LocaleContext} is active (e.g. a
     * direct unit test bypassing {@code DispatcherServlet}), silently defeating {@code
     * spring.web.locale-resolver: FIXED}'s "never guess based on server locale" intent.
     */
    private String resolve(String key, String defaultValue, HttpServletRequest request) {
        return messageSource.getMessage(key, null, defaultValue, RequestContextUtils.getLocale(request));
    }
}
