package com.allpets.api.reviews.service;

import com.allpets.api.reviews.client.GooglePlacesClient;
import com.allpets.api.reviews.client.PlaceReviews;
import com.allpets.api.reviews.domain.ReviewsCache;
import com.allpets.api.reviews.repository.ReviewsCacheRepository;
import java.time.OffsetDateTime;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * Owns the cached-reviews lifecycle (LLD §4.1, Epic 10):
 * <ul>
 *   <li><strong>Refresh</strong> — a daily {@code @Scheduled} job (plus one run on startup)
 *       fetches via the {@link GooglePlacesClient} port and replaces the single
 *       {@code reviews_cache} row in one atomic {@code save} (UPSERT of id = 1).</li>
 *   <li><strong>Fallback (10.7)</strong> — on ANY failure (exception, null, or empty result)
 *       the existing batch is left untouched; only a successful, non-empty fetch replaces it.
 *       So {@code fetched_at} is always the last <em>success</em> (staleness = now − fetched_at).</li>
 *   <li><strong>Read</strong> — {@link #currentCache()} reads the cache only; never a live
 *       Google call on the request path.</li>
 * </ul>
 * No {@code @Transactional}: a single-row {@code save} (merge) is atomic on its own, which
 * keeps {@code refresh()} directly invokable (scheduler, startup listener, tests) without the
 * self-invocation proxy trap.
 */
@Service
public class ReviewsService {

    private static final Logger log = LoggerFactory.getLogger(ReviewsService.class);

    private final GooglePlacesClient placesClient;
    private final ReviewsCacheRepository repository;

    public ReviewsService(GooglePlacesClient placesClient, ReviewsCacheRepository repository) {
        this.placesClient = placesClient;
        this.repository = repository;
    }

    /** Daily refresh at the configured time (default 03:00 America/Chicago). */
    @Scheduled(cron = "${allpets.reviews.refresh-cron}", zone = "${allpets.reviews.timezone}")
    public void scheduledRefresh() {
        refresh();
    }

    /** Populate the cache shortly after deploy rather than waiting for the first cron tick. */
    @EventListener(ApplicationReadyEvent.class)
    public void refreshOnStartup() {
        refresh();
    }

    /** Fetch and, only on a successful non-empty result, replace the cached batch. */
    public void refresh() {
        try {
            PlaceReviews fetched = placesClient.fetch();
            if (fetched == null || fetched.reviews() == null || fetched.reviews().isEmpty()) {
                log.debug("reviews refresh produced no reviews; keeping last-good cache");
                return;
            }
            ReviewsCache row = repository.findById(ReviewsCache.SINGLETON_ID).orElseGet(ReviewsCache::singleton);
            row.update(fetched.aggregateRating(), fetched.ratingCount(), fetched.reviews(), OffsetDateTime.now());
            repository.save(row);
            log.info("reviews cache refreshed: rating={}, count={}, reviews={}",
                    fetched.aggregateRating(), fetched.ratingCount(), fetched.reviews().size());
        } catch (Exception e) {
            // FALLBACK: keep the last-good batch. A failed run (fetch OR persist) must never
            // blank the cache, nor propagate out of the scheduler / ApplicationReadyEvent listener.
            log.warn("reviews refresh failed; keeping last-good cache: {}", e.toString());
        }
    }

    /** The current cached batch (empty if never successfully populated). */
    public Optional<ReviewsCache> currentCache() {
        return repository.findById(ReviewsCache.SINGLETON_ID);
    }
}
