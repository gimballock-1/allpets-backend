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
    // Phase-1 skeleton (20.1): web + health/metrics + input validation.
    // Persistence (JPA/Flyway/Postgres) is added in 20.2; mail in Epic 13.
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("io.micrometer:micrometer-registry-prometheus")

    annotationProcessor("org.springframework.boot:spring-boot-configuration-processor")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

// Expose method parameter names (cleaner Spring binding / validation messages).
tasks.withType<JavaCompile> {
    options.compilerArgs.add("-parameters")
    options.encoding = "UTF-8"
}

tasks.withType<Test> {
    useJUnitPlatform()
}
