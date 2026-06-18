package com.allpets.api.common.web;

import org.springframework.stereotype.Component;

/**
 * Phase-1 default {@link RateLimiter}: allows every request. Epic 14 (14.2/14.3) replaces
 * this with the real per-IP limiter (it can mark its bean {@code @Primary}).
 */
@Component
public class AllowAllRateLimiter implements RateLimiter {

    @Override
    public boolean tryAcquire(String clientIp) {
        return true;
    }
}
