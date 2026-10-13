package com.caderly.caderlyhr.common;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;

class NoDefaultProfileTest {

    // A baked-in active profile makes a deployment that forgets SPRING_PROFILES_ACTIVE run as
    // dev: the committed dev encryption key, the seeded admin and a non-Secure session cookie.
    @Test
    void applicationYml_doesNotActivateAnyProfile() throws IOException {
        var sources =
                new YamlPropertySourceLoader()
                        .load("application", new ClassPathResource("application.yml"));

        assertThat(sources)
                .extracting(PropertySource::getName)
                .isNotEmpty();
        for (PropertySource<?> source : sources) {
            assertThat(source.getProperty("spring.profiles.active")).isNull();
        }
    }
}
