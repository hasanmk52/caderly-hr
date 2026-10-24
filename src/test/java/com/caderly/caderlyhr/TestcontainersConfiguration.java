package com.caderly.caderlyhr;

import org.springframework.boot.devtools.restart.RestartScope;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

@TestConfiguration(proxyBeanMethods = false)
public class TestcontainersConfiguration {

    @Bean
    @ServiceConnection
    @RestartScope
    PostgreSQLContainer postgresContainer() {
        return new PostgreSQLContainer(DockerImageName.parse("postgres:17-alpine"))
                .withReuse(true);
    }

    /**
     * Cost 4 instead of production's 12: every login attempt in a test pays one BCrypt check, and
     * at cost 12 that made the auth tests the slowest in the suite. Primary, so both security
     * realms and every service that injects a {@code PasswordEncoder} get it. Production's cost
     * is pinned by {@code SecurityConfigPasswordEncoderTest}.
     */
    @Bean
    @Primary
    PasswordEncoder fastTestPasswordEncoder() {
        return new BCryptPasswordEncoder(4);
    }
}
