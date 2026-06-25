package com.allpets.api.config;

import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * CORS allowlist for the public API host ({@code api.allpets.skpodduturi.dev}) — 20.4.
 *
 * <p>In the happy path the browser never calls this host directly: the Next.js site
 * reaches the API through its own same-origin {@code /api} route-handler proxy whose
 * server-side fetch is cross-origin (Frontend LLD §4.2), and server-to-server calls
 * are not subject to CORS. This allowlist is therefore <strong>defense-in-depth</strong>
 * — if any browser-side code ever hits the API host directly, only the site origin is
 * permitted. Enforcement is in Spring (this bean), <strong>not</strong> at the ingress.
 *
 * <p>Origins are env-overridable via {@code ALLPETS_CORS_ALLOWED_ORIGINS} (comma-
 * separated) — see {@code application.yml}. Credentials are <strong>off</strong>: the
 * contact endpoint is unauthenticated and the API is cookie-/token-less, so there is no
 * reason to let the browser send credentials cross-origin (and {@code allowCredentials=true}
 * with a reflected origin would be unsafe).
 */
@Configuration
class CorsConfig implements WebMvcConfigurer {

    private final List<String> allowedOrigins;

    CorsConfig(@Value("${allpets.cors.allowed-origins:https://allpets.skpodduturi.dev}") List<String> allowedOrigins) {
        this.allowedOrigins = allowedOrigins;
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/**")
                .allowedOrigins(allowedOrigins.toArray(String[]::new))
                .allowedMethods("GET", "POST")   // the only API verbs in phase 1 (contact POST, reviews GET)
                .allowedHeaders("Content-Type")  // JSON body on POST /contact; reviews GET needs none
                .allowCredentials(false)
                .maxAge(3600);
    }
}
