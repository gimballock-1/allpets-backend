package com.allpets.api.common.web;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * Applies the per-IP rate limit to {@code POST /contact} <em>before</em> the request body is
 * ever read (14.2): as a {@link HandlerInterceptor} it runs ahead of {@code @RequestBody}
 * deserialization and validation, so malformed JSON and invalid payloads consume budget too,
 * and a flooding bot cannot dodge the limiter by sending garbage. Registered for the
 * {@code /contact} path in {@link RateLimitWebConfig}.
 *
 * <p>On denial: {@code 429} + {@code Retry-After} (seconds until the caller can actually
 * succeed) + the API's stable error-body shape. Denials are counted via Micrometer
 * ({@code allpets.contact.rate.limited}) and the WARN log line is sampled (first denial,
 * then every {@value #LOG_EVERY_N_DENIALS}th) so a flood cannot turn the log itself into an
 * amplification target. Count-only — never the client's payload (no PII in logs, req §8.4).
 */
@Component
public class ContactRateLimitInterceptor implements HandlerInterceptor {

    static final int LOG_EVERY_N_DENIALS = 100;

    private static final Logger log = LoggerFactory.getLogger(ContactRateLimitInterceptor.class);
    private static final String RATE_LIMITED_BODY = "{\"status\":\"rate_limited\"}";

    private final RateLimiter rateLimiter;
    private final ClientIpResolver clientIpResolver;
    private final Counter deniedCounter;
    private final AtomicLong denials = new AtomicLong();

    public ContactRateLimitInterceptor(RateLimiter rateLimiter, ClientIpResolver clientIpResolver,
                                       MeterRegistry meterRegistry) {
        this.rateLimiter = rateLimiter;
        this.clientIpResolver = clientIpResolver;
        this.deniedCounter = Counter.builder("allpets.contact.rate.limited")
                .description("Contact submissions denied by the per-IP rate limit")
                .register(meterRegistry);
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws IOException {
        if (!"POST".equalsIgnoreCase(request.getMethod())) {
            return true;   // CORS preflight etc. — only the write path is limited
        }

        RateLimiter.Decision decision = rateLimiter.tryAcquire(clientIpResolver.resolve(request));
        if (decision.allowed()) {
            return true;
        }

        deniedCounter.increment();
        long total = denials.incrementAndGet();
        if (total == 1 || total % LOG_EVERY_N_DENIALS == 0) {
            log.warn("contact submissions rate-limited (total denials={})", total);
        }

        response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
        response.setHeader("Retry-After", Long.toString(decision.retryAfterSeconds()));
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write(RATE_LIMITED_BODY);
        return false;
    }
}
