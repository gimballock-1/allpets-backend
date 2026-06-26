package com.allpets.api.reviews.web;

import com.allpets.api.reviews.domain.CachedReview;
import com.allpets.api.reviews.domain.ReviewsCache;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * {@code GET /reviews} response. A cold/empty cache returns the typed-empty form
 * ({@code reviews: []}, null aggregate) with a 200 — never a 5xx — so the site (8.5) can
 * degrade gracefully.
 */
public record ReviewsResponse(
        BigDecimal aggregateRating,
        Integer ratingCount,
        OffsetDateTime fetchedAt,
        List<CachedReview> reviews) {

    public static ReviewsResponse empty() {
        return new ReviewsResponse(null, null, null, List.of());
    }

    public static ReviewsResponse from(ReviewsCache cache) {
        return new ReviewsResponse(cache.getAggregateRating(), cache.getRatingCount(), cache.getFetchedAt(), cache.getReviews());
    }
}
