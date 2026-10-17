package com.caderly.caderlyhr.signup;

import com.caderly.caderlyhr.tenant.SlugPolicy;
import java.util.Locale;

/** The answer to "can I have this workspace address?" (ADR 0022 decision D). */
public enum AddressAvailability {
    AVAILABLE,
    REQUIRED,
    TOO_SHORT,
    INVALID_FORMAT,
    RESERVED,
    TAKEN;

    static AddressAvailability of(SlugPolicy.Problem problem) {
        return switch (problem) {
            case REQUIRED -> REQUIRED;
            case TOO_SHORT -> TOO_SHORT;
            case INVALID_FORMAT -> INVALID_FORMAT;
            case RESERVED -> RESERVED;
        };
    }

    public boolean isAvailable() {
        return this == AVAILABLE;
    }

    /** Doubles as the {@code CaderlyException} error code, e.g. {@code SIGNUP_ADDRESS_RESERVED}. */
    public String errorCode() {
        return "SIGNUP_ADDRESS_" + name();
    }

    /** The {@code messages.properties} key for the status line under the address field. */
    public String messageKey() {
        return "site.signup.address." + name().toLowerCase(Locale.ROOT).replace('_', '-');
    }
}
