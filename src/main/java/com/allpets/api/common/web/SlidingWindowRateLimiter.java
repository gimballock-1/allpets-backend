package com.allpets.api.common.web;

import java.time.InstantSource;
import java.util.ArrayDeque;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * In-memory sliding-window {@link RateLimiter} for {@code POST /contact} (14.2):
 * {@code perMinute} (default 5) AND {@code perHour} (default 30) per client IP.
 *
 * <p><strong>Why hand-rolled:</strong> the deployment is single-replica (LLD §3), so a
 * distributed limiter buys nothing; a sliding <em>log</em> of at most {@code perHour}
 * timestamps per IP is exact (no fixed-window burst-at-the-boundary artifacts), gives a
 * precise {@code Retry-After}, and is ~40 lines — a Bucket4j dependency would be strictly
 * more code surface for the same behavior at this scale.
 *
 * <p><strong>Bounded memory:</strong> the key map is an access-order LRU capped at
 * {@code maxTrackedIps} (default 10&nbsp;000; worst case ≈ {@code maxTrackedIps × perHour}
 * boxed longs — a few MB). A periodic sweep additionally drops IPs whose entire history
 * has aged out of the hour window, so the map shrinks back after a burst.
 *
 * <p>Only <em>allowed</em> requests consume budget; denied requests do not extend the
 * block, keeping {@code Retry-After} honest. All state is guarded by the instance lock —
 * fine at clinic-site traffic on one replica.
 */
@Component
public class SlidingWindowRateLimiter implements RateLimiter {

    private static final long MINUTE_MS = 60_000L;
    private static final long HOUR_MS = 3_600_000L;
    /** Sweep the whole map for fully-aged-out IPs every this many acquisitions. */
    private static final int SWEEP_EVERY = 2048;

    private final int perMinute;
    private final int perHour;
    private final InstantSource clock;

    /** clientIp -> ascending timestamps (ms) of allowed requests in the last hour. LRU-bounded. */
    private final LinkedHashMap<String, ArrayDeque<Long>> hitsByIp;
    private int acquiresSinceSweep;

    @Autowired
    public SlidingWindowRateLimiter(
            @Value("${allpets.rate-limit.contact.per-minute:5}") int perMinute,
            @Value("${allpets.rate-limit.contact.per-hour:30}") int perHour,
            @Value("${allpets.rate-limit.contact.max-tracked-ips:10000}") int maxTrackedIps) {
        this(perMinute, perHour, maxTrackedIps, InstantSource.system());
    }

    SlidingWindowRateLimiter(int perMinute, int perHour, int maxTrackedIps, InstantSource clock) {
        if (perMinute < 1 || perHour < 1 || maxTrackedIps < 1) {
            throw new IllegalArgumentException("rate-limit config values must be >= 1");
        }
        this.perMinute = perMinute;
        this.perHour = perHour;
        this.clock = clock;
        this.hitsByIp = new LinkedHashMap<>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, ArrayDeque<Long>> eldest) {
                return size() > maxTrackedIps;
            }
        };
    }

    @Override
    public synchronized Decision tryAcquire(String clientIp) {
        long now = clock.millis();
        maybeSweep(now);

        ArrayDeque<Long> hits = hitsByIp.computeIfAbsent(clientIp, ip -> new ArrayDeque<>());
        while (!hits.isEmpty() && hits.peekFirst() <= now - HOUR_MS) {
            hits.pollFirst();
        }

        if (hits.size() >= perHour) {
            return Decision.limit(secondsUntil(hits.peekFirst() + HOUR_MS, now));
        }

        long minuteFloor = now - MINUTE_MS;
        int inMinute = 0;
        long oldestInMinute = now;
        for (long ts : hits) {
            if (ts > minuteFloor) {
                if (inMinute == 0) {
                    oldestInMinute = ts;
                }
                inMinute++;
            }
        }
        if (inMinute >= perMinute) {
            return Decision.limit(secondsUntil(oldestInMinute + MINUTE_MS, now));
        }

        hits.addLast(now);
        return Decision.allow();
    }

    /** Seconds (rounded up, min 1) until {@code readyAtMs}. */
    private static long secondsUntil(long readyAtMs, long now) {
        return Math.max(1, Math.ceilDiv(readyAtMs - now, 1000L));
    }

    /** Drops IPs whose entire history has aged out, so idle keys don't linger until LRU eviction. */
    private void maybeSweep(long now) {
        if (++acquiresSinceSweep < SWEEP_EVERY) {
            return;
        }
        acquiresSinceSweep = 0;
        Iterator<ArrayDeque<Long>> it = hitsByIp.values().iterator();
        while (it.hasNext()) {
            ArrayDeque<Long> hits = it.next();
            if (hits.isEmpty() || hits.peekLast() <= now - HOUR_MS) {
                it.remove();
            }
        }
    }

    /** Test hook: number of IPs currently tracked. */
    synchronized int trackedIpCount() {
        return hitsByIp.size();
    }
}
