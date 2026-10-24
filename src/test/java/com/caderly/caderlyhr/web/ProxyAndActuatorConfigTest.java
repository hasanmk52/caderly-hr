package com.caderly.caderlyhr.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.caderly.caderlyhr.TestcontainersConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;
import org.springframework.test.context.ActiveProfiles;

/** CLAUDE.md §6 A05: pinned settings that fail silently if the key is misspelled or dropped. MockMvc is on only so
 * this shares the suite's common Spring context instead of starting its own. */
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("test")
@AutoConfigureMockMvc
@SpringBootTest
class ProxyAndActuatorConfigTest {

    @Autowired private Environment environment;

    @Test
    void forwardHeadersStrategy_isNative_soLinksBehindTheProxyAreHttps() {
        assertThat(environment.getProperty("server.forward-headers-strategy")).isEqualTo("native");
    }

    @Test
    void actuatorExposure_isHealthOnly() {
        assertThat(environment.getProperty("management.endpoints.web.exposure.include")).isEqualTo("health");
    }
}
