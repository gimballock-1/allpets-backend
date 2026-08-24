package com.allpets.api.contact;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.allpets.api.contact.domain.ContactSubmission;
import com.allpets.api.contact.repository.ContactSubmissionRepository;
import com.allpets.api.email.EmailNotifier;
import com.allpets.api.support.PostgresIntegrationTest;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.web.client.RestClient;

/**
 * End-to-end {@code POST /contact} rate limiting with the REAL {@code SlidingWindowRateLimiter}
 * and {@code ClientIpResolver} (no rate-limit mock): per-IP buckets keyed by the right-most
 * non-trusted {@code X-Forwarded-For} entry, 429 + {@code Retry-After} on breach, and the
 * honeypot consuming budget.
 *
 * <p>The limiter singleton is shared across the test methods of this class, so each test
 * uses its own client IPs. The test HTTP client connects from {@code 127.0.0.1} (trusted),
 * exactly like Traefik's in-cluster hop; the crafted XFF headers replay production shapes.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ContactRateLimitIntegrationTest extends PostgresIntegrationTest {

    private static final String PROXY_EGRESS = "10.42.0.9";   // in-cluster Next.js proxy hop

    @LocalServerPort
    private int port;

    private RestClient rest;

    @Autowired
    private ContactSubmissionRepository repository;

    @MockitoBean
    private EmailNotifier emailNotifier;

    @BeforeEach
    void setUp() {
        rest = RestClient.builder()
                .baseUrl("http://localhost:" + port)
                .defaultStatusHandler(HttpStatusCode::isError, (req, res) -> { })
                .build();
    }

    private ResponseEntity<Map> post(String forwardedFor, Map<String, String> body) {
        return rest.post().uri("/contact")
                .contentType(MediaType.APPLICATION_JSON)
                .header("X-Forwarded-For", forwardedFor)
                .body(body)
                .retrieve().toEntity(Map.class);
    }

    private static Map<String, String> validBody(String message) {
        return Map.of("name", "Jamie", "email", "jamie@example.com", "message", message);
    }

    @Test
    void perIpLimitEnforcedAndForwardedClientsGetSeparateBuckets() {
        // Both proxied clients share the SAME proxy egress hop — the catastrophic failure
        // mode would be both collapsing into one bucket. Prove they do not.
        String clientA = "203.0.113.10, " + PROXY_EGRESS;
        String clientB = "203.0.113.11, " + PROXY_EGRESS;
        long before = repository.count();

        for (int i = 1; i <= 5; i++) {
            ResponseEntity<Map> ok = post(clientA, validBody("hello " + i));
            assertThat(ok.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        }

        ResponseEntity<Map> limited = post(clientA, validBody("sixth"));
        assertThat(limited.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(limited.getBody()).containsEntry("status", "rate_limited");
        String retryAfter = limited.getHeaders().getFirst("Retry-After");
        assertThat(retryAfter).isNotNull();
        assertThat(Long.parseLong(retryAfter)).isBetween(1L, 60L);

        // A DIFFERENT forwarded client through the same proxy egress is unaffected.
        ResponseEntity<Map> other = post(clientB, validBody("other client"));
        assertThat(other.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);

        // 5 (client A) + 1 (client B) persisted; the 429 persisted nothing.
        assertThat(repository.count()).isEqualTo(before + 6);
        // The resolved REAL client IP (not the proxy egress) is what got stored.
        assertThat(repository.findAll())
                .extracting(ContactSubmission::getSourceIp)
                .contains("203.0.113.10", "203.0.113.11")
                .doesNotContain(PROXY_EGRESS);
    }

    @Test
    void spoofedLeftMostEntryCannotEscapeTheBucket() {
        // A direct bot varies a spoofed left-most XFF entry on every request; Traefik
        // appends the bot's real peer IP last. All requests must land in ONE bucket.
        String botIp = "198.51.100.77";

        for (int i = 1; i <= 5; i++) {
            ResponseEntity<Map> ok = post("203.0.113.2" + i + ", " + botIp, validBody("spam " + i));
            assertThat(ok.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        }

        ResponseEntity<Map> limited = post("203.0.113.99, " + botIp, validBody("spam 6"));

        assertThat(limited.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(limited.getHeaders().getFirst("Retry-After")).isNotNull();
    }

    @Test
    void honeypotRequestsAreDroppedButStillConsumeBudget() {
        String client = "203.0.113.30, " + PROXY_EGRESS;
        long before = repository.count();

        for (int i = 1; i <= 5; i++) {
            ResponseEntity<Map> ok = post(client, Map.of(
                    "name", "Bot", "email", "bot@example.com",
                    "message", "spam", "website", "http://spam.example"));
            // Indistinguishable from a real success — a bot must not learn it was caught.
            assertThat(ok.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
            assertThat(ok.getBody()).containsEntry("status", "received");
        }

        // Nothing persisted, nobody emailed …
        assertThat(repository.count()).isEqualTo(before);
        verify(emailNotifier, never()).sendContactNotification(any());

        // … yet the honeypot traffic consumed the caller's budget: a 6th (real) attempt is 429.
        ResponseEntity<Map> limited = post(client, validBody("real message"));
        assertThat(limited.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(repository.count()).isEqualTo(before);
    }
}
