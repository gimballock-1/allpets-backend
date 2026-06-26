package com.allpets.api.reviews;

import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.allpets.api.reviews.client.GooglePlacesClient;
import com.allpets.api.reviews.client.PlaceReviews;
import com.allpets.api.reviews.domain.CachedReview;
import com.allpets.api.reviews.domain.ReviewsCache;
import com.allpets.api.reviews.repository.ReviewsCacheRepository;
import com.allpets.api.reviews.service.ReviewsService;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * Pure-unit fallback proof (10.7): a <em>persistence</em> failure during {@code refresh()}
 * must be swallowed — it must not propagate out of the scheduler or the
 * {@code ApplicationReadyEvent} listener (which could otherwise crash startup).
 */
class ReviewsServiceUnitTest {

    @Test
    void persistenceFailureDoesNotPropagate() {
        GooglePlacesClient client = mock(GooglePlacesClient.class);
        ReviewsCacheRepository repo = mock(ReviewsCacheRepository.class);
        when(client.fetch()).thenReturn(new PlaceReviews(new BigDecimal("4.5"), 5,
                List.of(new CachedReview("A", null, null, 5, "good", "2026-01-01T00:00:00Z", "1 day ago"))));
        when(repo.findById(ReviewsCache.SINGLETON_ID)).thenReturn(Optional.empty());
        when(repo.save(any())).thenThrow(new RuntimeException("db down"));

        ReviewsService service = new ReviewsService(client, repo);

        assertThatNoException().isThrownBy(service::refresh);
    }
}
