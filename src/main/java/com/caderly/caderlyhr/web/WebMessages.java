package com.caderly.caderlyhr.web;

import com.caderly.caderlyhr.common.CaderlyException;
import java.util.Locale;
import org.springframework.context.MessageSource;

/**
 * Shared message-resolution helper (ADR 0013) for controllers that surface a {@link
 * CaderlyException}'s detail text directly — a rejected form field, an inline upload-error list,
 * a {@code pageError} model attribute — outside {@link GlobalExceptionHandler}'s own request
 * pipeline. Mirrors that class's key scheme (the exception's {@code errorCode}, lowercased and
 * hyphenated, under the {@code error.} prefix) so the same failure renders identical text
 * whether it reaches the user via the error page, a toast, or a bound field error.
 */
final class WebMessages {

    private WebMessages() {}

    static String errorDetail(MessageSource messageSource, CaderlyException exception, Locale locale) {
        String key = "error." + exception.errorCode().toLowerCase(Locale.ROOT).replace('_', '-');
        return messageSource.getMessage(key, null, exception.getMessage(), locale);
    }
}
