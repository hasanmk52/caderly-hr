package com.caderly.caderlyhr.signup;

/** What happened when a confirmation link was opened. */
public sealed interface VerifyResult {

    /** The workspace exists; {@code acceptUrl} is the first Admin's single-use set-password link. */
    record Created(String slug, String acceptUrl) implements VerifyResult {
        @Override
        public String toString() {
            // acceptUrl carries a live token.
            return "Created[slug=" + slug + "]";
        }
    }

    /** Unknown, already used or expired — deliberately indistinguishable. */
    record LinkInvalid() implements VerifyResult {}

    /** The address was taken between sign-up and confirmation; nothing was created. */
    record AddressTaken(String slug) implements VerifyResult {}
}
