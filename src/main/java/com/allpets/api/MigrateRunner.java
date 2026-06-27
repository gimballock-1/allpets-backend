package com.allpets.api;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * Deploy-time migration gate (issue 15.9), active only under the {@code migrate} profile.
 *
 * <p>Spring Boot runs Flyway during context refresh (before runners) and JPA
 * {@code ddl-auto=validate} then checks the entity mapping against the freshly-migrated
 * schema — so by the time this runner executes, the database is migrated and validated.
 * Under the {@code migrate} profile the web server is disabled
 * ({@code spring.main.web-application-type=none}, see {@code application.yml}); this runner
 * exits the JVM so the Kubernetes migration Job <em>completes</em> instead of idling
 * (the {@code @EnableScheduling} thread pool would otherwise keep a non-daemon thread
 * alive). {@code System.exit(0)} still runs the registered Spring shutdown hook, so the
 * context (and the datasource pool) closes cleanly.
 *
 * <p>If Flyway fails, context startup throws before this runner is reached and Spring Boot
 * exits non-zero — failing the Job, so CD never rolls the app onto a bad schema.
 */
@Component
@Profile("migrate")
class MigrateRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(MigrateRunner.class);

    @Override
    public void run(ApplicationArguments args) {
        log.info("Flyway migration + schema validation complete (migrate profile) — exiting 0.");
        System.exit(0);
    }
}
