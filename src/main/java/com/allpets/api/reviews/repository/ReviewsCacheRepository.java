package com.allpets.api.reviews.repository;

import com.allpets.api.reviews.domain.ReviewsCache;
import org.springframework.data.jpa.repository.JpaRepository;

/** Persistence for the single-row {@code reviews_cache} (keyed by the fixed id = 1). */
public interface ReviewsCacheRepository extends JpaRepository<ReviewsCache, Short> {
}
