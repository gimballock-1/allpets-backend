package com.allpets.api;

import com.allpets.api.support.PostgresIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Smoke test: the Spring application context loads. With persistence wired (20.2) this
 * also proves Flyway applies cleanly and Hibernate {@code validate} matches the live
 * schema — a context-load failure would surface either.
 */
@SpringBootTest
class AllpetsApiApplicationTests extends PostgresIntegrationTest {

    @Test
    void contextLoads() {
        // Intentionally empty — a failure to load the context fails the test.
    }
}
