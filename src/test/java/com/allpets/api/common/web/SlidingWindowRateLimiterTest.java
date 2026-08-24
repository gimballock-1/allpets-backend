package com.allpets.api.common.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.allpets.api.common.web.RateLimiter.Decision;
import java.time.Instant;
import java.time.InstantSource;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for the hand-rolled sliding-window limiter (14.2): 5/minute AND 30/hour per
 * IP, honest {@code Retry-After}, per-IP isolation, and a bounded key map.
 */
class SlidingWindowRateLimiterTest {

    private static final int PER_MINUTE = 5;
    private static final int PER_HOUR = 30;

    /** Deterministic, manually-advanced clock. */
    private static final class MutableClock implements InstantSource {
        private long millis = 1_700_000_000_000L;

        @Override
        public Instant instant() {
            return Instant.ofEpochMilli(millis);
        }

        @Override
        public long millis() {
            return millis;
        }

        void advanceMillis(long delta) {
            millis += delta;
        }
    }

    private final MutableClock clock = new MutableClock();
    private final SlidingWindowRateLimiter limiter =
            new SlidingWindowRateLimiter(PER_MINUTE, PER_HOUR, 10_000, clock);

    @Test
    void sixthRequestWithinAMinuteIsDeniedWithRetryAfter() {
        for (int i = 0; i < PER_MINUTE; i++) {
            assertThat(limiter.tryAcquire("203.0.113.7").allowed()).isTrue();
            clock.advanceMillis(1_000);
        }

        Decision denied = limiter.tryAcquire("203.0.113.7");

        assertThat(denied.allowed()).isFalse();
        // First hit was 5s ago; it leaves the minute window in 55s.
        assertThat(denied.retryAfterSeconds()).isBetween(1L, 60L).isEqualTo(55L);
    }

    @Test
    void secondIpIsUnaffectedByAnExhaustedFirstIp() {
        for (int i = 0; i < PER_MINUTE; i++) {
            limiter.tryAcquire("203.0.113.7");
        }
        assertThat(limiter.tryAcquire("203.0.113.7").allowed()).isFalse();

        assertThat(limiter.tryAcquire("198.51.100.8").allowed()).isTrue();
    }

    @Test
    void minuteWindowSlides() {
        for (int i = 0; i < PER_MINUTE; i++) {
            limiter.tryAcquire("203.0.113.7");
        }
        assertThat(limiter.tryAcquire("203.0.113.7").allowed()).isFalse();

        clock.advanceMillis(60_001);

        assertThat(limiter.tryAcquire("203.0.113.7").allowed()).isTrue();
    }

    @Test
    void deniedRequestsDoNotConsumeBudget() {
        for (int i = 0; i < PER_MINUTE; i++) {
            limiter.tryAcquire("203.0.113.7");
        }
        // Hammering while blocked must not push the recovery point out.
        for (int i = 0; i < 20; i++) {
            assertThat(limiter.tryAcquire("203.0.113.7").allowed()).isFalse();
            clock.advanceMillis(1_000);
        }

        clock.advanceMillis(60_001);   // well past the original burst
        assertThat(limiter.tryAcquire("203.0.113.7").allowed()).isTrue();
    }

    @Test
    void hourlyCapKicksInEvenWhenEachMinuteIsUnderItsLimit() {
        // 6 bursts of 5, one burst per ~minute: every minute is within 5/min, but the
        // 31st request in the hour must be denied by the hourly cap.
        for (int burst = 0; burst < 6; burst++) {
            for (int i = 0; i < PER_MINUTE; i++) {
                assertThat(limiter.tryAcquire("203.0.113.7").allowed()).isTrue();
            }
            clock.advanceMillis(61_000);
        }

        Decision denied = limiter.tryAcquire("203.0.113.7");

        assertThat(denied.allowed()).isFalse();
        // Recovery needs the OLDEST of the 30 hits to age out of the hour window —
        // far longer than a minute-window wait.
        assertThat(denied.retryAfterSeconds()).isGreaterThan(60L).isLessThanOrEqualTo(3_600L);

        clock.advanceMillis(denied.retryAfterSeconds() * 1_000);
        assertThat(limiter.tryAcquire("203.0.113.7").allowed()).isTrue();
    }

    @Test
    void retryAfterIsAtLeastOneSecond() {
        for (int i = 0; i < PER_MINUTE; i++) {
            limiter.tryAcquire("203.0.113.7");
        }
        clock.advanceMillis(59_990);   // 10ms short of the window edge

        Decision denied = limiter.tryAcquire("203.0.113.7");

        assertThat(denied.allowed()).isFalse();
        assertThat(denied.retryAfterSeconds()).isEqualTo(1L);
    }

    @Test
    void keyMapIsBoundedByLruEviction() {
        SlidingWindowRateLimiter bounded = new SlidingWindowRateLimiter(5, 30, 3, clock);

        for (int i = 0; i < 10; i++) {
            bounded.tryAcquire("203.0.113." + i);
        }

        assertThat(bounded.trackedIpCount()).isEqualTo(3);
        // Evicted-and-back IP simply starts a fresh budget (permissive on eviction).
        assertThat(bounded.tryAcquire("203.0.113.0").allowed()).isTrue();
    }

    @Test
    void sweepDropsIpsWhoseHistoryAgedOut() {
        for (int i = 0; i < 100; i++) {
            limiter.tryAcquire("203.0.113." + i);
        }
        assertThat(limiter.trackedIpCount()).isEqualTo(100);

        clock.advanceMillis(3_600_001);   // everything is now stale
        // Drive past the sweep threshold with a single active key.
        for (int i = 0; i < 2_100; i++) {
            limiter.tryAcquire("198.51.100.8");
            clock.advanceMillis(30_000);  // spread out so this key itself stays small
        }

        assertThat(limiter.trackedIpCount()).isEqualTo(1);
    }
}
