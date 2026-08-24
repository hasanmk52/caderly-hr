package com.caderly.caderlyhr.common;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;

/**
 * Single-use secrets sent by email — invite and password-reset tokens (CLAUDE.md §6 A02, PRD
 * §19.1).
 *
 * <p>The contract: {@link #generate()} returns the raw token, which goes in the email and is then
 * discarded; only {@link #hash(String)} of it is persisted. A database leak therefore yields
 * nothing an attacker can redeem.
 *
 * <p>Lives in {@code common} rather than {@code security} because {@code identity} needs it and
 * {@code security} already depends on {@code identity} — the other placement would make a package
 * cycle that ArchUnit rejects.
 */
public final class SecureToken {

    /** PRD §19.1: 32 bytes of cryptographic randomness. */
    private static final int TOKEN_BYTES = 32;

    private static final SecureRandom RANDOM = new SecureRandom();

    private SecureToken() {}

    /** A fresh raw token, URL-safe so it can be carried as a query parameter unencoded. */
    public static String generate() {
        byte[] bytes = new byte[TOKEN_BYTES];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /**
     * SHA-256 hex of a raw token, for storage and lookup.
     *
     * <p>A plain hash rather than BCrypt on purpose, and it is not the same trade-off as password
     * storage: this input is 32 bytes of full-entropy random, so there is no dictionary to attack
     * and no reason to pay a work factor. It also has to be <em>looked up</em> by value, which a
     * salted hash cannot do.
     */
    public static String hash(String rawToken) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(rawToken.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            // SHA-256 is required of every JVM; if it is missing the platform is broken.
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }
}
