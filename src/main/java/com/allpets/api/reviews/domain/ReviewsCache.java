package com.allpets.api.reviews.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * The single current batch of cached Google reviews (the {@code reviews_cache} table,
 * LLD §4.1). Exactly one row exists, keyed by the fixed id {@link #SINGLETON_ID} = 1 —
 * the scheduled refresh overwrites it in place, so there is no history (consistent with
 * Google's caching terms: the cache is a short-lived buffer, not a datastore).
 *
 * <p>Schema is owned by Flyway ({@code V1__init.sql}); {@code ddl-auto=validate} keeps
 * this mapping and the live schema in lock-step. {@code reviews} maps the {@code jsonb}
 * column to a typed list via {@link SqlTypes#JSON}. The id is assigned (not generated).
 */
@Entity
@Table(name = "reviews_cache")
public class ReviewsCache {

    /** The fixed primary key — there is only ever one row. */
    public static final short SINGLETON_ID = 1;

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private Short id;

    @Column(name = "aggregate_rating")
    private BigDecimal aggregateRating;

    @Column(name = "rating_count")
    private Integer ratingCount;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "reviews", nullable = false)
    private List<CachedReview> reviews = List.of();

    @Column(name = "fetched_at")
    private OffsetDateTime fetchedAt;

    protected ReviewsCache() {
        // for JPA
    }

    /** A fresh, empty singleton row (id = 1) for the first successful refresh to populate. */
    public static ReviewsCache singleton() {
        ReviewsCache c = new ReviewsCache();
        c.id = SINGLETON_ID;
        return c;
    }

    /** Replace the cached batch with a freshly fetched one. */
    public void update(BigDecimal aggregateRating, Integer ratingCount, List<CachedReview> reviews, OffsetDateTime fetchedAt) {
        this.aggregateRating = aggregateRating;
        this.ratingCount = ratingCount;
        this.reviews = reviews == null ? List.of() : List.copyOf(reviews);
        this.fetchedAt = fetchedAt;
    }

    public Short getId() {
        return id;
    }

    public BigDecimal getAggregateRating() {
        return aggregateRating;
    }

    public Integer getRatingCount() {
        return ratingCount;
    }

    public List<CachedReview> getReviews() {
        return reviews;
    }

    public OffsetDateTime getFetchedAt() {
        return fetchedAt;
    }
}
