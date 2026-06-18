package com.allpets.api.contact;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.allpets.api.common.web.RateLimiter;
import com.allpets.api.contact.domain.ContactSubmission;
import com.allpets.api.contact.repository.ContactSubmissionRepository;
import com.allpets.api.email.EmailNotifier;
import com.allpets.api.support.PostgresIntegrationTest;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * Exercises {@code POST /contact} over real HTTP against a real Postgres: validation,
 * persistence, the email-notifier trigger, the honeypot drop, and the rate-limit hook.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ContactEndpointTest extends PostgresIntegrationTest {

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private ContactSubmissionRepository repository;

    @MockitoBean
    private EmailNotifier emailNotifier;   // verify it is triggered without sending real mail

    @MockitoBean
    private RateLimiter rateLimiter;       // drive the limit decision

    @BeforeEach
    void allowByDefault() {
        when(rateLimiter.tryAcquire(any())).thenReturn(true);
    }

    @Test
    void validSubmissionPersistsAndNotifies() {
        long before = repository.count();

        ResponseEntity<Map> r = rest.postForEntity("/contact",
                Map.of("name", "Jamie", "email", "jamie@example.com", "message", "New patient?"),
                Map.class);

        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(r.getBody()).containsEntry("status", "received");
        assertThat(repository.count()).isEqualTo(before + 1);
        verify(emailNotifier).sendContactNotification(any(ContactSubmission.class));
    }

    @Test
    void invalidInputReturns400WithFieldErrors() {
        long before = repository.count();

        ResponseEntity<Map> r = rest.postForEntity("/contact",
                Map.of("name", "", "email", "not-an-email", "message", ""),
                Map.class);

        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(r.getBody()).containsEntry("status", "invalid");
        @SuppressWarnings("unchecked")
        Map<String, String> errors = (Map<String, String>) r.getBody().get("errors");
        assertThat(errors).containsKeys("name", "email", "message");
        assertThat(repository.count()).isEqualTo(before);   // nothing persisted
        verify(emailNotifier, never()).sendContactNotification(any());
    }

    @Test
    void honeypotIsSilentlyDropped() {
        long before = repository.count();

        ResponseEntity<Map> r = rest.postForEntity("/contact",
                Map.of("name", "Bot", "email", "bot@example.com", "message", "spam", "website", "http://spam.example"),
                Map.class);

        // Same success shape as a real submission, but nothing persisted / no email.
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(r.getBody()).containsEntry("status", "received");
        assertThat(repository.count()).isEqualTo(before);
        verify(emailNotifier, never()).sendContactNotification(any());
    }

    @Test
    void notifierFailureIsNonFatalAndSubmissionPersists() {
        // A mail failure must NOT roll back the persisted submission nor surface as 5xx (LLD §6).
        doThrow(new RuntimeException("smtp down")).when(emailNotifier).sendContactNotification(any());
        long before = repository.count();

        ResponseEntity<Map> r = rest.postForEntity("/contact",
                Map.of("name", "Jamie", "email", "jamie@example.com", "message", "still saved?"),
                Map.class);

        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(repository.count()).isEqualTo(before + 1);   // persisted despite the send failure
    }

    @Test
    void overRateLimitReturns429() {
        when(rateLimiter.tryAcquire(any())).thenReturn(false);
        long before = repository.count();

        ResponseEntity<Map> r = rest.postForEntity("/contact",
                Map.of("name", "Jamie", "email", "jamie@example.com", "message", "hello"),
                Map.class);

        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(r.getHeaders().getFirst("Retry-After")).isEqualTo("60");
        assertThat(repository.count()).isEqualTo(before);
        verify(emailNotifier, never()).sendContactNotification(any());
    }
}
