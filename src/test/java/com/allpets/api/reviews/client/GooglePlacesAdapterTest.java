package com.allpets.api.reviews.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.allpets.api.reviews.ReviewsProperties;
import com.allpets.api.reviews.domain.CachedReview;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/**
 * Unit-tests the Places API (New) normalization — the half of 10.3 the mocked-port tests
 * don't reach: the ≤5 clamp, {@code text}→{@code originalText} fallback, rating rounding to
 * numeric(2,1), null-{@code authorAttribution} safety, and the auth/field-mask headers.
 */
class GooglePlacesAdapterTest {

    // 6 reviews (to prove the clamp); rating 4.65 (rounds to 4.7); review[1] has only
    // originalText; review[2] has no authorAttribution.
    private static final String PLACES_JSON = """
            {
              "id": "ChIJtest",
              "displayName": { "text": "All Pets Veterinary Hospital" },
              "rating": 4.65,
              "userRatingCount": 312,
              "reviews": [
                {"rating":5,"text":{"text":"t1"},"authorAttribution":{"displayName":"A1","uri":"u1","photoUri":"p1"},"publishTime":"2026-06-10T14:21:00Z","relativePublishTimeDescription":"2 weeks ago"},
                {"rating":4,"originalText":{"text":"orig2"},"authorAttribution":{"displayName":"A2"},"publishTime":"2026-06-01T00:00:00Z","relativePublishTimeDescription":"3 weeks ago"},
                {"rating":3,"text":{"text":"t3"},"publishTime":"2026-05-01T00:00:00Z","relativePublishTimeDescription":"a month ago"},
                {"rating":5,"text":{"text":"t4"},"authorAttribution":{"displayName":"A4"}},
                {"rating":2,"text":{"text":"t5"},"authorAttribution":{"displayName":"A5"}},
                {"rating":1,"text":{"text":"t6"},"authorAttribution":{"displayName":"A6"}}
              ]
            }
            """;

    @Test
    void normalizesPlacesResponse() {
        RestClient.Builder builder = RestClient.builder().baseUrl("https://places.googleapis.com/v1");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        GooglePlacesAdapter adapter = new GooglePlacesAdapter(
                builder.build(),
                new ReviewsProperties("ChIJtest", "fake-key", "0 0 3 * * *", "America/Chicago"));

        server.expect(requestTo("https://places.googleapis.com/v1/places/ChIJtest"))
                .andExpect(header("X-Goog-Api-Key", "fake-key"))
                .andExpect(header("X-Goog-FieldMask", "id,displayName,rating,userRatingCount,reviews"))
                .andRespond(withSuccess(PLACES_JSON, MediaType.APPLICATION_JSON));

        PlaceReviews result = adapter.fetch();

        assertThat(result).isNotNull();
        assertThat(result.aggregateRating()).isEqualByComparingTo("4.7"); // 4.65 -> 4.7 (HALF_UP, numeric(2,1))
        assertThat(result.ratingCount()).isEqualTo(312);
        List<CachedReview> reviews = result.reviews();
        assertThat(reviews).hasSize(5);                                    // clamped from 6
        assertThat(reviews.get(0).author()).isEqualTo("A1");
        assertThat(reviews.get(0).authorPhotoUri()).isEqualTo("p1");
        assertThat(reviews.get(0).time()).isEqualTo("2026-06-10T14:21Z");
        assertThat(reviews.get(1).text()).isEqualTo("orig2");             // originalText fallback
        assertThat(reviews.get(2).author()).isNull();                     // null authorAttribution tolerated
        server.verify();
    }

    @Test
    void noOpWhenNotConfigured() {
        // No key / place-id -> no HTTP call, returns null (service keeps the empty cache).
        RestClient.Builder builder = RestClient.builder().baseUrl("https://places.googleapis.com/v1");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        GooglePlacesAdapter adapter = new GooglePlacesAdapter(
                builder.build(),
                new ReviewsProperties("", "", "0 0 3 * * *", "America/Chicago"));

        assertThat(adapter.fetch()).isNull();
        server.verify(); // no request expected/made
    }
}
