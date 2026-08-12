package com.helyx.helyxhr.common;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Column-level encryption for sensitive employee fields (government IDs, bank details;
 * CLAUDE.md §6 A02, ADR 0008) — AES-256-GCM via the JDK's built-in {@code javax.crypto}, no new
 * Maven dependency.
 *
 * <p>Registered as a Spring {@code @Component} rather than instantiated by the persistence
 * provider: Hibernate resolves {@code @Converter} beans through its {@code ManagedBeanRegistry},
 * which Spring Boot backs with the application context, so the {@code @Value}-injected key below
 * gets real constructor injection instead of a no-arg JPA instantiation.
 *
 * <p>Storage format: a fresh random 12-byte IV (GCM's recommended nonce size) prepended to the
 * ciphertext+tag, all as one {@code bytea}. A fresh IV per encryption is the whole point of GCM's
 * security — reusing one with the same key breaks confidentiality outright — so encrypting the
 * same plaintext twice never produces the same bytes, which is expected and fine: these columns
 * are never queried by equality.
 */
@Component
@Converter
public class CryptoConverter implements AttributeConverter<String, byte[]> {

    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final int GCM_IV_BYTES = 12;
    private static final int GCM_TAG_BITS = 128;

    private static final SecureRandom RANDOM = new SecureRandom();

    private final SecretKeySpec key;

    CryptoConverter(@Value("${helyx.people.encryption-key}") String base64Key) {
        this.key = new SecretKeySpec(Base64.getDecoder().decode(base64Key), "AES");
    }

    @Override
    public byte[] convertToDatabaseColumn(@Nullable String attribute) {
        if (attribute == null) {
            return null;
        }
        try {
            byte[] iv = new byte[GCM_IV_BYTES];
            RANDOM.nextBytes(iv);
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(GCM_TAG_BITS, iv));
            byte[] ciphertext = cipher.doFinal(attribute.getBytes(StandardCharsets.UTF_8));

            byte[] result = new byte[iv.length + ciphertext.length];
            System.arraycopy(iv, 0, result, 0, iv.length);
            System.arraycopy(ciphertext, 0, result, iv.length, ciphertext.length);
            return result;
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("Failed to encrypt column value", exception);
        }
    }

    @Override
    public @Nullable String convertToEntityAttribute(byte[] dbData) {
        if (dbData == null) {
            return null;
        }
        try {
            byte[] iv = new byte[GCM_IV_BYTES];
            System.arraycopy(dbData, 0, iv, 0, GCM_IV_BYTES);
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(GCM_TAG_BITS, iv));
            byte[] plaintext = cipher.doFinal(dbData, GCM_IV_BYTES, dbData.length - GCM_IV_BYTES);
            return new String(plaintext, StandardCharsets.UTF_8);
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("Failed to decrypt column value", exception);
        }
    }
}
