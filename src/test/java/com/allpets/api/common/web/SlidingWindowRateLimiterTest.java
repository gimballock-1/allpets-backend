package com.allpets.api.common.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.allpets.api.common.web.RateLimiter.Decision;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Instant;
import java.time.InstantSource;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for the hand-rolled sliding-window limiter (14.2): 5/minute AND 30/hour per
 * bucket, honest {@code Retry-After} (max over violated windows), per-bucket isolation,
 * IPv6 /64 key aggregation, and a bounded key map with saturation metrics.
 */
class SlidingWindowRateLimiterTest {

    private static final int PER_MINUTE = 5;
    private static final int PER_HOUR = 30;
    private static final int IPV6_PREFIX = 64;

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
    private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
    private final SlidingWindowRateLimiter limiter = new SlidingWindowRateLimiter(
            PER_MINUTE, PER_HOUR, 10_000, IPV6_PREFIX, meterRegistry, clock);

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
    void retryAfterCoversTheMinuteWindowWhenBothWindowsAreSaturated() {
        // Regression (Codex P2-3): 25 hits early, then the last 5 just before the hour
        // window starts freeing. The hour window frees in 1s, the minute window in 56s —
        // Retry-After must report the LATER recovery, not the hourly one.
        for (int burst = 0; burst < 5; burst++) {
            for (int i = 0; i < PER_MINUTE; i++) {
                assertThat(limiter.tryAcquire("203.0.113.7").allowed()).isTrue();
            }
            clock.advanceMillis(61_000);
        }
        clock.advanceMillis(3_290_000);   // t = 3595s; first burst ages out at t = 3600s
        for (int i = 0; i < PER_MINUTE; i++) {
            assertThat(limiter.tryAcquire("203.0.113.7").allowed()).isTrue();
            if (i < PER_MINUTE - 1) {
                clock.advanceMillis(1_000);
            }
        }
        // t = 3599s: 30 in the hour (violated, frees in 1s), 5 in the minute (violated,
        // frees in 56s).
        Decision denied = limiter.tryAcquire("203.0.113.7");
        assertThat(denied.allowed()).isFalse();
        assertThat(denied.retryAfterSeconds()).isEqualTo(56L);

        // Two seconds later the hour window HAS freed — the minute window must still deny.
        clock.advanceMillis(2_000);
        Decision stillDenied = limiter.tryAcquire("203.0.113.7");
        assertThat(stillDenied.allowed()).isFalse();
        assertThat(stillDenied.retryAfterSeconds()).isEqualTo(54L);

        clock.advanceMillis(54_000);
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
    void twoAddressesInOneIpv6Slash64ShareABucket() {
        // Regression (Codex P2-2): a bot rotating through its delegated /64 must not get
        // a fresh budget per address.
        for (int i = 0; i < PER_MINUTE; i++) {
            assertThat(limiter.tryAcquire("2001:db8:1:2::" + (i + 1)).allowed()).isTrue();
        }

        Decision denied = limiter.tryAcquire("2001:db8:1:2:ffff:ffff:ffff:ffff");

        assertThat(denied.allowed()).isFalse();
    }

    @Test
    void differentIpv6Slash64GetsItsOwnBucket() {
        for (int i = 0; i < PER_MINUTE; i++) {
            limiter.tryAcquire("2001:db8:1:2::1");
        }
        assertThat(limiter.tryAcquire("2001:db8:1:2::2").allowed()).isFalse();

        assertThat(limiter.tryAcquire("2001:db8:1:3::1").allowed()).isTrue();
    }

    @Test
    void keyMapIsBoundedByLruEvictionAndCountsEvictions() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        SlidingWindowRateLimiter bounded =
                new SlidingWindowRateLimiter(5, 30, 3, IPV6_PREFIX, registry, clock);

        for (int i = 0; i < 10; i++) {
            bounded.tryAcquire("203.0.113." + i);
        }

        assertThat(bounded.trackedIpCount()).isEqualTo(3);
        // Saturation is observable: 10 inserts over a cap of 3 -> 7 evictions.
        assertThat(registry.get("allpets.ratelimit.evictions").counter().count()).isEqualTo(7.0);
        assertThat(registry.get("allpets.ratelimit.tracked.ips").gauge().value()).isEqualTo(3.0);
        // Evicted-and-back IP simply starts a fresh budget (documented fail-open).
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
