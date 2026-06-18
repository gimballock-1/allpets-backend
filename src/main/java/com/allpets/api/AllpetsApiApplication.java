package com.allpets.api;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Entry point for the allpets backend API.
 *
 * <p>Phase-1 responsibilities (Epic 20): receive contact-form submissions, serve a
 * Google-reviews cache, send transactional email, and expose health/metrics. The
 * service is structured domain-first (see {@code package-info}) so the phase-2
 * pet-profiles + multi-branch application slots in with its own design pass.
 */
@SpringBootApplication
public class AllpetsApiApplication {

    public static void main(String[] args) {
        SpringApplication.run(AllpetsApiApplication.class, args);
    }
}
