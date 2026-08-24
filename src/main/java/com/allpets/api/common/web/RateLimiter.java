package com.allpets.api.common.web;

/**
 * Port for per-client rate limiting on public write endpoints.
 *
 * <p>Implemented by {@link SlidingWindowRateLimiter} (14.2): 5/minute and 30/hour per
 * resolved client IP on {@code POST /contact}. The controller calls this on every request
 * and translates a denial into {@code 429} + {@code Retry-After} (seconds).
 */
public interface RateLimiter {

    /**
     * Records an attempt for {@code clientIp} and decides whether it may proceed.
     *
     * @param clientIp the caller's IP as resolved by {@link ClientIpResolver} (right-most
     *                 non-trusted {@code X-Forwarded-For} entry — never a raw spoofable header)
     * @return the decision; when denied it carries the seconds the caller should wait
     */
    Decision tryAcquire(String clientIp);

    /**
     * Outcome of a rate-limit check. {@code retryAfterSeconds} is meaningful only when
     * {@code allowed} is {@code false} (it is {@code 0} on an allow).
     */
    record Decision(boolean allowed, long retryAfterSeconds) {

        public static Decision allow() {
            return new Decision(true, 0);
        }

        /** Denied; {@code retryAfterSeconds} is clamped to at least 1s so the header is honest. */
        public static Decision limit(long retryAfterSeconds) {
            return new Decision(false, Math.max(1, retryAfterSeconds));
        }
    }
}
