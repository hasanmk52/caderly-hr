package com.caderly.caderlyhr.security;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;

/** CLAUDE.md §6 A02: production hashes passwords with BCrypt cost 12. Tests run a cheaper encoder. */
class SecurityConfigPasswordEncoderTest {

    @Test
    void passwordEncoder_inProduction_usesBcryptCost12() {
        PasswordEncoder encoder = new SecurityConfig().passwordEncoder();

        String hash = encoder.encode("C0rrectHorseBattery");

        assertThat(hash).startsWith("$2a$12$");
        assertThat(encoder.matches("C0rrectHorseBattery", hash)).isTrue();
    }
}
