package com.caderly.caderlyhr.common;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * A single injectable {@link Clock} so time-dependent logic — token expiry, lockout windows,
 * outbox backoff — can be driven deterministically in tests instead of sleeping.
 */
@Configuration(proxyBeanMethods = false)
class ClockConfig {

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }
}
