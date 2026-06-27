plugins {
    java
    id("org.springframework.boot") version "3.5.15"
    id("io.spring.dependency-management") version "1.1.7"
}

group = "com.allpets"
version = "0.1.0"
description = "allpets backend API — Spring Boot service (contact form, reviews cache, transactional email, health)"

// JDK-pinned build, independent of the runner's default JDK (LLD §3.1).
java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
}

repositories {
    mavenCentral()
}

dependencies {
    // Web + health/metrics + input validation (20.1).
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("io.micrometer:micrometer-registry-prometheus")

    // Persistence (20.2): JPA + Flyway-managed Postgres schema. Mail is Epic 13.
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.flywaydb:flyway-core")
    implementation("org.flywaydb:flyway-database-postgresql")
    runtimeOnly("org.postgresql:postgresql")

    annotationProcessor("org.springframework.boot:spring-boot-configuration-processor")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")

    // Real Postgres for tests without Docker (native binary): verifies Flyway
    // migrations + JPA `validate` mapping end-to-end. CI's drift guard (15.4) uses
    // a separate throwaway Postgres service.
    testImplementation("io.zonky.test:embedded-postgres:2.2.2")
    // Match the prod server minor (postgres:16.4) so test behaviour mirrors prod.
    testImplementation(platform("io.zonky.test.postgres:embedded-postgres-binaries-bom:16.4.0"))
}

// Expose method parameter names (cleaner Spring binding / validation messages).
tasks.withType<JavaCompile> {
    options.compilerArgs.add("-parameters")
    options.encoding = "UTF-8"
}

tasks.withType<Test> {
    useJUnitPlatform()
}
