package com.helyx.helyxhr.common;

import org.springframework.http.HttpStatus;

/** A referenced entity does not exist, or is not visible to the current tenant. */
public class NotFoundException extends HelyxException {

    public NotFoundException(String errorCode, String message) {
        super(errorCode, message);
    }

    @Override
    public HttpStatus status() {
        return HttpStatus.NOT_FOUND;
    }
}
