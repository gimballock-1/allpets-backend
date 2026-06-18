package com.allpets.api.common.web;

/**
 * Port for per-client rate limiting on public write endpoints.
 *
 * <p>Phase 1 ships a permissive default ({@link AllowAllRateLimiter}); <strong>Epic 14</strong>
 * (14.2/14.3) supplies the real per-IP limiter (token bucket + {@code Retry-After}). The
 * controller calls this on every {@code POST /contact}, so the enforcement point exists now
 * and only the policy is swapped later.
 */
public interface RateLimiter {

    /**
     * @param clientIp the caller's IP (resolved from {@code X-Forwarded-For} behind Traefik)
     * @return {@code true} if the request may proceed; {@code false} if over the limit
     */
    boolean tryAcquire(String clientIp);
}
