package com.allpets.api.contact.web;

import com.allpets.api.common.web.ClientIpResolver;
import com.allpets.api.common.web.RateLimiter;
import com.allpets.api.contact.service.ContactService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code POST /contact} — public, unauthenticated. Validates input, applies the per-IP
 * rate limit (14.2) and the honeypot (14.3), then persists + triggers a staff notification.
 * The site's same-origin {@code /api/contact} proxy is the caller (Frontend LLD §4.2).
 *
 * <p>Ordering matters: the rate limit is checked <em>before</em> the honeypot so honeypot
 * submissions still consume the caller's budget — a flooding bot hits 429 regardless of
 * whether it trips the honeypot. All log lines here are count-only (no name/email/message —
 * no PII in logs, req §8.4).
 */
@RestController
public class ContactController {

    private static final Logger log = LoggerFactory.getLogger(ContactController.class);
    private static final Map<String, String> RECEIVED = Map.of("status", "received");

    private final ContactService contactService;
    private final RateLimiter rateLimiter;
    private final ClientIpResolver clientIpResolver;

    public ContactController(ContactService contactService, RateLimiter rateLimiter,
                             ClientIpResolver clientIpResolver) {
        this.contactService = contactService;
        this.rateLimiter = rateLimiter;
        this.clientIpResolver = clientIpResolver;
    }

    @PostMapping("/contact")
    public ResponseEntity<Map<String, String>> submit(@Valid @RequestBody ContactRequest request,
                                                       HttpServletRequest http) {
        // Right-most non-trusted X-Forwarded-For entry (see ClientIpResolver): proxied
        // visitors resolve to their real IP, and a direct bot cannot spoof its bucket.
        String clientIp = clientIpResolver.resolve(http);

        RateLimiter.Decision decision = rateLimiter.tryAcquire(clientIp);
        if (!decision.allowed()) {
            log.warn("contact submission rate-limited");
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                    .header("Retry-After", Long.toString(decision.retryAfterSeconds()))
                    .body(Map.of("status", "rate_limited"));
        }

        // Honeypot tripped: silently drop and return the same success so a bot can't
        // distinguish acceptance from rejection. Nothing persisted, no email.
        if (request.website() != null && !request.website().isBlank()) {
            log.info("contact submission dropped (honeypot)");
            return ResponseEntity.accepted().body(RECEIVED);
        }

        contactService.submit(
                request.name(), request.email(), request.message(),
                clientIp, http.getHeader("User-Agent"));

        return ResponseEntity.accepted().body(RECEIVED);
    }
}
