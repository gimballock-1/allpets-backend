package com.allpets.api.reviews.client;

import com.allpets.api.reviews.domain.CachedReview;
import java.math.BigDecimal;
import java.util.List;

/** A normalized fetch result from {@link GooglePlacesClient} (aggregate + up to 5 reviews). */
public record PlaceReviews(BigDecimal aggregateRating, Integer ratingCount, List<CachedReview> reviews) {
}
