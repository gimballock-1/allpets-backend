package com.allpets.api.reviews;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.allpets.api.reviews.client.GooglePlacesClient;
import com.allpets.api.reviews.client.PlaceReviews;
import com.allpets.api.reviews.domain.CachedReview;
import com.allpets.api.reviews.repository.ReviewsCacheRepository;
import com.allpets.api.reviews.service.ReviewsService;
import com.allpets.api.support.PostgresIntegrationTest;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * {@code GET /reviews} over real HTTP: a cold cache returns a typed-empty 200 (never 5xx);
 * a populated cache serves the batch. Reads never trigger a live Google call.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ReviewsEndpointTest extends PostgresIntegrationTest {

    @Autowired
    private TestRestTemplate rest;

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
    void coldCacheReturnsTypedEmpty200WithCacheControl() {
        clearInvocations(placesClient); // forget the startup refresh's fetch()
        ResponseEntity<Map> r = rest.getForEntity("/reviews", Map.class);

        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(r.getBody()).containsEntry("reviews", List.of());
        assertThat(r.getBody().get("aggregateRating")).isNull();
        assertThat(r.getHeaders().getCacheControl()).contains("max-age=300").contains("public");
        verifyNoInteractions(placesClient); // the read path must NOT call Google
    }

    @Test
    void populatedCacheIsServed() {
        when(placesClient.fetch()).thenReturn(new PlaceReviews(new BigDecimal("4.7"), 312,
                List.of(new CachedReview("Jane D.", null, null, 5, "Great",
                        "2026-06-10T14:21:00Z", "2 weeks ago"))));
        service.refresh();
        clearInvocations(placesClient); // forget the refresh's fetch()

        ResponseEntity<Map> r = rest.getForEntity("/reviews", Map.class);

        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(r.getBody().get("ratingCount")).isEqualTo(312);
        verifyNoInteractions(placesClient); // served from cache, no live Google call
        assertThat(r.getBody().get("aggregateRating")).isEqualTo(4.7);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> reviews = (List<Map<String, Object>>) r.getBody().get("reviews");
        assertThat(reviews).hasSize(1);
        assertThat(reviews.get(0)).containsEntry("author", "Jane D.").containsEntry("rating", 5);
    }
}
