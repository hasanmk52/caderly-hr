package com.helyx.helyxhr.common;

import org.springframework.http.HttpStatus;

/**
 * Input failed a business rule that Bean Validation could not express — one that needs to consult
 * persistent state or another aggregate. Field-shaped input errors should use {@code @Valid} and
 * bean-validation annotations instead (CLAUDE.md §6 A03).
 */
public class ValidationException extends HelyxException {

    public ValidationException(String errorCode, String message) {
        super(errorCode, message);
    }

    @Override
    public HttpStatus status() {
        return HttpStatus.BAD_REQUEST;
    }
}
