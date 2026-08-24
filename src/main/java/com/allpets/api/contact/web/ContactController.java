package com.allpets.api.contact.web;

import com.allpets.api.common.web.ClientIpResolver;
import com.allpets.api.contact.service.ContactService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.Validator;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code POST /contact} — public, unauthenticated. The site's same-origin
 * {@code /api/contact} proxy is the caller (Frontend LLD §4.2).
 *
 * <p>Ordering (14.2/14.3): the per-IP rate limit runs in
 * {@code ContactRateLimitInterceptor} BEFORE the body is even deserialized — malformed and
 * invalid requests consume budget, and honeypot traffic hits 429 like any other flood. Here
 * the honeypot is checked BEFORE validation (deliberately no {@code @Valid}): an invalid
 * honeypotted payload must get the same fake success as a valid one, never a 400 that
 * reveals non-acceptance. Only clean requests are then bean-validated (same 400 shape via
 * {@code ApiExceptionHandler}) and submitted. All log lines are count-only (no
 * name/email/message — no PII in logs, req §8.4).
 */
@RestController
public class ContactController {

    private static final Logger log = LoggerFactory.getLogger(ContactController.class);
    private static final Map<String, String> RECEIVED = Map.of("status", "received");

    private final ContactService contactService;
    private final ClientIpResolver clientIpResolver;
    private final Validator validator;

    public ContactController(ContactService contactService, ClientIpResolver clientIpResolver,
                             Validator validator) {
        this.contactService = contactService;
        this.clientIpResolver = clientIpResolver;
        this.validator = validator;
    }

    @PostMapping("/contact")
    public ResponseEntity<Map<String, String>> submit(@RequestBody ContactRequest request,
                                                       HttpServletRequest http) {
        // Honeypot tripped: silently drop and return the same success (202) a real
        // submission gets, so a bot can't distinguish acceptance from rejection — even an
        // otherwise-invalid payload. Nothing persisted, no email, count-only log.
        if (request.website() != null && !request.website().isBlank()) {
            log.info("contact submission dropped (honeypot)");
            return ResponseEntity.accepted().body(RECEIVED);
        }

        Set<ConstraintViolation<ContactRequest>> violations = validator.validate(request);
        if (!violations.isEmpty()) {
            throw new ConstraintViolationException(violations);   // -> 400, ApiExceptionHandler
        }

        // Right-most non-trusted X-Forwarded-For entry (see ClientIpResolver) — the full
        // client address (not the limiter's aggregated IPv6 bucket) for spam triage.
        contactService.submit(
                request.name(), request.email(), request.message(),
                clientIpResolver.resolve(http), http.getHeader("User-Agent"));

        return ResponseEntity.accepted().body(RECEIVED);
    }
}
