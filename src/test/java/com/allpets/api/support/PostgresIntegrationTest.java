package com.allpets.api.support;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Base for tests that need a real Postgres. Starts one native embedded instance per JVM
 * (no Docker) and points the Spring datasource at it, so Flyway migrations and Hibernate
 * {@code validate} run against the same engine production uses.
 *
 * <p>The {@code pgcrypto} and {@code citext} extensions are pre-created here to mirror
 * ADR 4.1 — in production they are created at DB init, so the {@code app_svc} role never
 * needs {@code CREATE EXTENSION}.
 */
public abstract class PostgresIntegrationTest {

    protected static final EmbeddedPostgres POSTGRES;

    static {
        try {
            POSTGRES = EmbeddedPostgres.start();
            try (var conn = POSTGRES.getPostgresDatabase().getConnection();
                 var stmt = conn.createStatement()) {
                stmt.execute("CREATE EXTENSION IF NOT EXISTS pgcrypto");
                stmt.execute("CREATE EXTENSION IF NOT EXISTS citext");
            }
        } catch (Exception e) {
            throw new IllegalStateException("embedded Postgres failed to start", e);
        }
    }

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> POSTGRES.getJdbcUrl("postgres", "postgres"));
        registry.add("spring.datasource.username", () -> "postgres");
        registry.add("spring.datasource.password", () -> "postgres");
    }
}
