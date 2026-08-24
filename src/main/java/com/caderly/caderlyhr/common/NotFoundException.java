package com.caderly.caderlyhr.common;

import org.springframework.http.HttpStatus;

/** A referenced entity does not exist, or is not visible to the current tenant. */
public class NotFoundException extends CaderlyException {

    public NotFoundException(String errorCode, String message) {
        super(errorCode, message);
    }

    @Override
    public HttpStatus status() {
        return HttpStatus.NOT_FOUND;
    }
}
