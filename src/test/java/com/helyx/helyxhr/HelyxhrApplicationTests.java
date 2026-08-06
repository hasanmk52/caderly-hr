package com.helyx.helyxhr;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.context.annotation.Import;

@Import(TestcontainersConfiguration.class)
@ActiveProfiles("test")
@SpringBootTest
class HelyxhrApplicationTests {

    @Test
    void contextLoads() {}
}
