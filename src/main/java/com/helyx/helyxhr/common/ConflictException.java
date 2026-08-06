package com.helyx.helyxhr.common;

import org.springframework.http.HttpStatus;

/** The request is well-formed but conflicts with current state — e.g. a duplicate email. */
public class ConflictException extends HelyxException {

    public ConflictException(String errorCode, String message) {
        super(errorCode, message);
    }

    @Override
    public HttpStatus status() {
        return HttpStatus.CONFLICT;
    }
}
