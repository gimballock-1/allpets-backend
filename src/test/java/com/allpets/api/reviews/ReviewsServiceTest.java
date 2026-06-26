package com.allpets.api.reviews;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.allpets.api.reviews.client.GooglePlacesClient;
import com.allpets.api.reviews.client.PlaceReviews;
import com.allpets.api.reviews.domain.CachedReview;
import com.allpets.api.reviews.domain.ReviewsCache;
import com.allpets.api.reviews.repository.ReviewsCacheRepository;
import com.allpets.api.reviews.service.ReviewsService;
import com.allpets.api.support.PostgresIntegrationTest;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * The refresh lifecycle against a real Postgres with the Google Places port mocked:
 * a good fetch populates the single row; any failure/empty result keeps the last-good batch (10.7).
 */
@SpringBootTest
class ReviewsServiceTest extends PostgresIntegrationTest {

    private static final PlaceReviews BATCH = new PlaceReviews(
            new BigDecimal("4.7"), 312,
            List.of(new CachedReview("Jane D.", "https://maps/contrib/1", "https://photo", 5,
                    "Great care for my dog", "2026-06-10T14:21:00Z", "2 weeks ago")));

    @Autowired
    private ReviewsService service;

    @Autowired
    private ReviewsCacheRepository repository;

    @MockitoBean
    private GooglePlacesClient placesClient;

    @BeforeEach
    void clean() {
        repository.deleteAll();
    }

    @Test
    void successfulFetchPopulatesTheSingleRow() {
        when(placesClient.fetch()).thenReturn(BATCH);

        service.refresh();

        ReviewsCache row = repository.findById(ReviewsCache.SINGLETON_ID).orElseThrow();
        assertThat(row.getAggregateRating()).isEqualByComparingTo("4.7");
        assertThat(row.getRatingCount()).isEqualTo(312);
        assertThat(row.getReviews()).hasSize(1);
        assertThat(row.getReviews().get(0).author()).isEqualTo("Jane D.");
        assertThat(row.getReviews().get(0).text()).isEqualTo("Great care for my dog");
        assertThat(row.getFetchedAt()).isNotNull();
    }

    @Test
    void fetchFailureKeepsTheLastGoodBatch() {
        when(placesClient.fetch()).thenReturn(BATCH);
        service.refresh(); // populate
        OffsetDateTime firstFetchedAt = repository.findById(ReviewsCache.SINGLETON_ID).orElseThrow().getFetchedAt();

        when(placesClient.fetch()).thenThrow(new RuntimeException("Google unreachable"));
        service.refresh(); // must NOT change the cache

        ReviewsCache row = repository.findById(ReviewsCache.SINGLETON_ID).orElseThrow();
        assertThat(row.getReviews()).hasSize(1);
        assertThat(row.getFetchedAt().toInstant()).isEqualTo(firstFetchedAt.toInstant());
    }

    @Test
    void emptyFetchDoesNotOverwriteWithNothing() {
        when(placesClient.fetch()).thenReturn(BATCH);
        service.refresh(); // populate

        when(placesClient.fetch()).thenReturn(new PlaceReviews(null, null, List.of()));
        service.refresh(); // empty -> keep last-good

        assertThat(repository.findById(ReviewsCache.SINGLETON_ID).orElseThrow().getReviews()).hasSize(1);
    }

    @Test
    void nullFetchLeavesTheCacheEmpty() {
        when(placesClient.fetch()).thenReturn(null); // adapter returns null when unconfigured

        service.refresh();

        assertThat(repository.findById(ReviewsCache.SINGLETON_ID)).isEmpty();
    }
}
