package com.allpets.api.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.allpets.api.support.PostgresIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestClient;

/**
 * Verifies the 20.4 CORS allowlist over real HTTP: a browser preflight from the site
 * origin is allowed; any other origin is rejected. CORS is enforced in Spring
 * ({@link CorsConfig}), not at the ingress.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class CorsConfigTest extends PostgresIntegrationTest {

    private static final String SITE_ORIGIN = "https://allpets.skpodduturi.dev";

    @LocalServerPort
    private int port;

    private RestClient rest;

    @BeforeEach
    void setUp() {
        rest = RestClient.builder()
                .baseUrl("http://localhost:" + port)
                .defaultStatusHandler(HttpStatusCode::isError, (req, res) -> { })
                .build();
    }

    @Test
    void preflightFromSiteOriginIsAllowed() {
        ResponseEntity<String> r = preflight(SITE_ORIGIN);

        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(r.getHeaders().getAccessControlAllowOrigin()).isEqualTo(SITE_ORIGIN);
        // the JSON Content-Type header the contact POST sends must be in the allow-list
        assertThat(r.getHeaders().getAccessControlAllowHeaders()).contains("Content-Type");
    }

    @Test
    void preflightFromOtherOriginIsRejected() {
        ResponseEntity<String> r = preflight("https://evil.example.com");

        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(r.getHeaders().getAccessControlAllowOrigin()).isNull();
    }

    /** A CORS preflight: OPTIONS carrying Origin + Access-Control-Request-Method. */
    private ResponseEntity<String> preflight(String origin) {
        return rest.method(HttpMethod.OPTIONS).uri("/contact")
                .header(HttpHeaders.ORIGIN, origin)
                .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, HttpMethod.POST.name())
                .header(HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS, "Content-Type")
                .retrieve().toEntity(String.class);
    }
}
