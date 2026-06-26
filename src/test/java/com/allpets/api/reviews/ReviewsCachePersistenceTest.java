package com.allpets.api.reviews;

import static org.assertj.core.api.Assertions.assertThat;

import com.allpets.api.reviews.domain.CachedReview;
import com.allpets.api.reviews.domain.ReviewsCache;
import com.allpets.api.reviews.repository.ReviewsCacheRepository;
import com.allpets.api.support.PostgresIntegrationTest;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Verifies the JPA mapping over the Flyway-owned {@code reviews_cache}: the fixed-id single
 * row, {@code numeric(2,1)} rating, and the {@code jsonb} reviews list round-trip. (The
 * mapping itself is also proven at startup by {@code ddl-auto=validate}.)
 */
@SpringBootTest
class ReviewsCachePersistenceTest extends PostgresIntegrationTest {

    @Autowired
    private ReviewsCacheRepository repository;

    @BeforeEach
    void clean() {
        repository.deleteAll();
    }

    @Test
    void roundTripsTheSingleRowIncludingJsonbReviews() {
        ReviewsCache c = ReviewsCache.singleton();
        c.update(new BigDecimal("4.6"), 100,
                List.of(new CachedReview("A", "u", "p", 5, "good", "2026-01-01T00:00:00Z", "1 day ago")),
                OffsetDateTime.parse("2026-06-25T12:00:00Z"));
        repository.save(c);

        ReviewsCache loaded = repository.findById(ReviewsCache.SINGLETON_ID).orElseThrow();
        assertThat(loaded.getId()).isEqualTo((short) 1);
        assertThat(loaded.getAggregateRating()).isEqualByComparingTo("4.6");
        assertThat(loaded.getRatingCount()).isEqualTo(100);
        assertThat(loaded.getReviews()).hasSize(1);
        assertThat(loaded.getReviews().get(0).text()).isEqualTo("good");
        assertThat(loaded.getReviews().get(0).rating()).isEqualTo(5);
        assertThat(loaded.getFetchedAt()).isNotNull();
    }

    @Test
    void newEntityIsTheFixedSingletonId() {
        assertThat(ReviewsCache.singleton().getId()).isEqualTo(ReviewsCache.SINGLETON_ID);
        assertThat(repository.count()).isZero();
    }

    @Test
    void savingAFreshSingletonWhenTheRowExistsUpdatesInPlace() {
        ReviewsCache first = ReviewsCache.singleton();
        first.update(new BigDecimal("4.0"), 10, List.of(), OffsetDateTime.parse("2026-01-01T00:00:00Z"));
        repository.save(first); // INSERT id=1

        // A fresh singleton (id=1, assigned) saved while the row already exists must MERGE to an
        // UPDATE, not a duplicate-key INSERT — this is the path orElseGet(singleton) can hit.
        ReviewsCache second = ReviewsCache.singleton();
        second.update(new BigDecimal("4.8"), 250,
                List.of(new CachedReview("B", null, null, 5, "newer", "2026-06-01T00:00:00Z", "1 day ago")),
                OffsetDateTime.parse("2026-06-25T00:00:00Z"));
        repository.save(second);

        assertThat(repository.count()).isEqualTo(1);
        ReviewsCache loaded = repository.findById(ReviewsCache.SINGLETON_ID).orElseThrow();
        assertThat(loaded.getAggregateRating()).isEqualByComparingTo("4.8");
        assertThat(loaded.getRatingCount()).isEqualTo(250);
        assertThat(loaded.getReviews()).hasSize(1);
    }
}
