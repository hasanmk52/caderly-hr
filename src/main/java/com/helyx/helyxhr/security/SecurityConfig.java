package com.helyx.helyxhr.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Phase 0 placeholder: permits the Hello page, static/webjar assets, and the health check so the
 * app is reachable with no auth in place yet. Real form-login security lands in Phase 1.2.
 */
@Configuration
class SecurityConfig {

  @Bean
  SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
    http.authorizeHttpRequests(
        authorize ->
            authorize
                .requestMatchers("/", "/webjars/**", "/css/**", "/actuator/health")
                .permitAll()
                .anyRequest()
                .authenticated());
    return http.build();
  }
}
