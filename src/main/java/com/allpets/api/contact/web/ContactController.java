package com.allpets.api.contact.web;

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
 * {@code POST /contact} — public, unauthenticated. Validates input, applies a per-IP
 * rate-limit hook and a honeypot, then persists + triggers a staff notification. The
 * site's same-origin {@code /api/contact} proxy is the caller (Frontend LLD §4.2).
 */
@RestController
public class ContactController {

    private static final Logger log = LoggerFactory.getLogger(ContactController.class);
    private static final Map<String, String> RECEIVED = Map.of("status", "received");

    private final ContactService contactService;
    private final RateLimiter rateLimiter;

    public ContactController(ContactService contactService, RateLimiter rateLimiter) {
        this.contactService = contactService;
        this.rateLimiter = rateLimiter;
    }

    @PostMapping("/contact")
    public ResponseEntity<Map<String, String>> submit(@Valid @RequestBody ContactRequest request,
                                                       HttpServletRequest http) {
        // X-Forwarded-For is honoured via server.forward-headers-strategy=framework.
        // NOTE for 14.2: inbound XFF is client-spoofable, so the per-IP limiter is only
        // sound if Traefik strips/overwrites XFF at the edge (trust just Traefik's hop).
        String clientIp = http.getRemoteAddr();

        if (!rateLimiter.tryAcquire(clientIp)) {
            log.warn("contact submission rate-limited");
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                    .header("Retry-After", "60")
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
