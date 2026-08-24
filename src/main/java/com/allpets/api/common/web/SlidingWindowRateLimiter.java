package com.allpets.api.common.web;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.net.InetAddress;
import java.net.UnknownHostException;
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
 * {@code perMinute} (default 5) AND {@code perHour} (default 30) per client bucket.
 *
 * <p><strong>Bucket key:</strong> IPv4 clients are keyed by address. IPv6 clients are keyed
 * by their {@code /ipv6KeyPrefixBits} prefix (default /64) — ISPs delegate at least a /64
 * per subscriber, so a bot rotating through its 2^64 addresses shares ONE budget instead of
 * getting a fresh one per address. The full address still reaches persistence/triage
 * untouched (the aggregation happens here, on the limiter key only).
 *
 * <p><strong>Why hand-rolled:</strong> the deployment is single-replica (LLD §3), so a
 * distributed limiter buys nothing; a sliding <em>log</em> of at most {@code perHour}
 * timestamps per bucket is exact (no fixed-window burst-at-the-boundary artifacts), gives a
 * precise {@code Retry-After}, and is ~40 lines — a Bucket4j dependency would be strictly
 * more code surface for the same behavior at this scale.
 *
 * <p><strong>Retry-After:</strong> recovery is computed for <em>every</em> violated window
 * and the maximum wins — when both windows are saturated the caller is told when it can
 * actually succeed, not merely when the hour window frees while the minute window still
 * denies.
 *
 * <p><strong>Bounded memory:</strong> the key map is an access-order LRU capped at
 * {@code maxTrackedIps} (default 10&nbsp;000; worst case ≈ {@code maxTrackedIps × perHour}
 * boxed longs — a few MB). A periodic sweep additionally drops buckets whose entire history
 * has aged out of the hour window, so the map shrinks back after a burst. At the cap the
 * limiter fails <em>open</em>: the least-recently-seen bucket is evicted and a returning
 * client starts a fresh budget — acceptable for anti-spam (never lock out legitimate
 * users because the map is full), and observable via the
 * {@code allpets.ratelimit.evictions} counter and {@code allpets.ratelimit.tracked.ips}
 * gauge (a climbing eviction rate means the cap is saturated and should be raised).
 *
 * <p>Only <em>allowed</em> requests consume budget; denied requests do not extend the
 * block, keeping {@code Retry-After} honest. All state is guarded by the instance lock —
 * fine at clinic-site traffic on one replica.
 */
@Component
public class SlidingWindowRateLimiter implements RateLimiter {

    private static final long MINUTE_MS = 60_000L;
    private static final long HOUR_MS = 3_600_000L;
    /** Sweep the whole map for fully-aged-out buckets every this many acquisitions. */
    private static final int SWEEP_EVERY = 2048;

    private final int perMinute;
    private final int perHour;
    private final int ipv6KeyPrefixBits;
    private final InstantSource clock;
    private final Counter evictions;

    /** bucket key -> ascending timestamps (ms) of allowed requests in the last hour. LRU-bounded. */
    private final LinkedHashMap<String, ArrayDeque<Long>> hitsByBucket;
    private int acquiresSinceSweep;

    @Autowired
    public SlidingWindowRateLimiter(
            @Value("${allpets.rate-limit.contact.per-minute:5}") int perMinute,
            @Value("${allpets.rate-limit.contact.per-hour:30}") int perHour,
            @Value("${allpets.rate-limit.contact.max-tracked-ips:10000}") int maxTrackedIps,
            @Value("${allpets.rate-limit.contact.ipv6-key-prefix:64}") int ipv6KeyPrefixBits,
            MeterRegistry meterRegistry) {
        this(perMinute, perHour, maxTrackedIps, ipv6KeyPrefixBits, meterRegistry, InstantSource.system());
    }

    SlidingWindowRateLimiter(int perMinute, int perHour, int maxTrackedIps, int ipv6KeyPrefixBits,
                             MeterRegistry meterRegistry, InstantSource clock) {
        if (perMinute < 1 || perHour < 1 || maxTrackedIps < 1) {
            throw new IllegalArgumentException("rate-limit config values must be >= 1");
        }
        if (ipv6KeyPrefixBits < 1 || ipv6KeyPrefixBits > 128) {
            throw new IllegalArgumentException("ipv6-key-prefix must be within 1..128");
        }
        this.perMinute = perMinute;
        this.perHour = perHour;
        this.ipv6KeyPrefixBits = ipv6KeyPrefixBits;
        this.clock = clock;
        this.evictions = Counter.builder("allpets.ratelimit.evictions")
                .description("Rate-limit buckets evicted because the LRU key map hit max-tracked-ips "
                        + "(fail-open; a climbing rate means the cap is saturated)")
                .register(meterRegistry);
        Gauge.builder("allpets.ratelimit.tracked.ips", this, SlidingWindowRateLimiter::trackedIpCount)
                .description("Rate-limit buckets currently tracked")
                .register(meterRegistry);
        this.hitsByBucket = new LinkedHashMap<>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, ArrayDeque<Long>> eldest) {
                if (size() > maxTrackedIps) {
                    evictions.increment();
                    return true;
                }
                return false;
            }
        };
    }

    @Override
    public synchronized Decision tryAcquire(String clientIp) {
        long now = clock.millis();
        maybeSweep(now);

        ArrayDeque<Long> hits = hitsByBucket.computeIfAbsent(bucketKey(clientIp), key -> new ArrayDeque<>());
        while (!hits.isEmpty() && hits.peekFirst() <= now - HOUR_MS) {
            hits.pollFirst();
        }

        // Evaluate BOTH windows; when limited, the caller must wait for the LAST one to free.
        long retryAfterSeconds = 0;
        if (hits.size() >= perHour) {
            retryAfterSeconds = secondsUntil(hits.peekFirst() + HOUR_MS, now);
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
            retryAfterSeconds = Math.max(retryAfterSeconds, secondsUntil(oldestInMinute + MINUTE_MS, now));
        }
        if (retryAfterSeconds > 0) {
            return Decision.limit(retryAfterSeconds);
        }

        hits.addLast(now);
        return Decision.allow();
    }

    /**
     * IPv4 (and any unparseable input) keys pass through unchanged; IPv6 addresses collapse
     * to their {@code /ipv6KeyPrefixBits} network in {@code prefix/bits} text form.
     */
    private String bucketKey(String clientIp) {
        if (clientIp == null || clientIp.indexOf(':') < 0) {
            return clientIp;
        }
        try {
            byte[] bytes = InetAddress.ofLiteral(clientIp).getAddress();
            if (bytes.length != 16) {
                return clientIp;
            }
            int fullBytes = ipv6KeyPrefixBits / 8;
            int remainderBits = ipv6KeyPrefixBits % 8;
            for (int i = fullBytes + (remainderBits > 0 ? 1 : 0); i < bytes.length; i++) {
                bytes[i] = 0;
            }
            if (remainderBits > 0) {
                bytes[fullBytes] &= (byte) (0xFF << (8 - remainderBits));
            }
            return InetAddress.getByAddress(bytes).getHostAddress() + "/" + ipv6KeyPrefixBits;
        } catch (IllegalArgumentException | UnknownHostException e) {
            return clientIp;
        }
    }

    /** Seconds (rounded up, min 1) until {@code readyAtMs}. */
    private static long secondsUntil(long readyAtMs, long now) {
        return Math.max(1, Math.ceilDiv(readyAtMs - now, 1000L));
    }

    /** Drops buckets whose entire history has aged out, so idle keys don't linger until LRU eviction. */
    private void maybeSweep(long now) {
        if (++acquiresSinceSweep < SWEEP_EVERY) {
            return;
        }
        acquiresSinceSweep = 0;
        Iterator<ArrayDeque<Long>> it = hitsByBucket.values().iterator();
        while (it.hasNext()) {
            ArrayDeque<Long> hits = it.next();
            if (hits.isEmpty() || hits.peekLast() <= now - HOUR_MS) {
                it.remove();
            }
        }
    }

    /** Buckets currently tracked (also exported as the {@code allpets.ratelimit.tracked.ips} gauge). */
    synchronized int trackedIpCount() {
        return hitsByBucket.size();
    }
}
