package com.helyx.helyxhr.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Base64;
import org.junit.jupiter.api.Test;

/** ADR 0008: AES-256-GCM round trip, fresh-IV-per-call, and tamper detection. No Spring context needed. */
class CryptoConverterTest {

    private static final String KEY = Base64.getEncoder().encodeToString(new byte[32]);

    private final CryptoConverter converter = new CryptoConverter(KEY);

    @Test
    void roundTrip_returnsTheOriginalValue() {
        byte[] encrypted = converter.convertToDatabaseColumn("AB1234567");

        assertThat(converter.convertToEntityAttribute(encrypted)).isEqualTo("AB1234567");
    }

    @Test
    void convertToDatabaseColumn_calledTwiceForTheSameValue_producesDifferentBytes() {
        // A fresh random IV per call is the point of GCM (ADR 0008 Decision A) — reusing one
        // would break confidentiality, so identical plaintext must never yield identical bytes.
        byte[] first = converter.convertToDatabaseColumn("secret");
        byte[] second = converter.convertToDatabaseColumn("secret");

        assertThat(first).isNotEqualTo(second);
    }

    @Test
    void convertToEntityAttribute_onTamperedCiphertext_throws() {
        byte[] encrypted = converter.convertToDatabaseColumn("secret");
        encrypted[encrypted.length - 1] ^= 0x01; // flip a bit inside the GCM tag/ciphertext

        assertThatThrownBy(() -> converter.convertToEntityAttribute(encrypted))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void nullValues_passThroughUnchanged() {
        assertThat(converter.convertToDatabaseColumn(null)).isNull();
        assertThat(converter.convertToEntityAttribute(null)).isNull();
    }
}
