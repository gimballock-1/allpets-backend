package com.allpets.api;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Smoke test: the Spring application context loads with the phase-1 skeleton wiring.
 * Catches misconfiguration (bad beans, missing config) before the image is built.
 */
@SpringBootTest
class AllpetsApiApplicationTests {

    @Test
    void contextLoads() {
        // Intentionally empty — a failure to load the context fails the test.
    }
}
