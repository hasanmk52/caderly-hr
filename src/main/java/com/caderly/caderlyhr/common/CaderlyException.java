package com.caderly.caderlyhr.common;

import org.springframework.http.HttpStatus;

/**
 * Base of the application's exception hierarchy (CLAUDE.md §7). Every subclass carries a stable
 * {@code errorCode} that {@code ProblemDetailExceptionHandler} renders as the RFC 7807 {@code type}
 * suffix, so clients can branch on the code rather than on message text.
 *
 * <p>Messages here reach the user. They must never contain a password, token, or hash (CLAUDE.md
 * §6 A02/A09) — pass identifiers, not secrets.
 */
public abstract class CaderlyException extends RuntimeException {

    private final String errorCode;

    protected CaderlyException(String errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public String errorCode() {
        return errorCode;
    }

    /** HTTP status this failure maps to. */
    public abstract HttpStatus status();
}
