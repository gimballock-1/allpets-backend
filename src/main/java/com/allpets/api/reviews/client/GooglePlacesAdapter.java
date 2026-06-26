package com.allpets.api.reviews.client;

import com.allpets.api.reviews.ReviewsProperties;
import com.allpets.api.reviews.domain.CachedReview;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;

/**
 * Calls the <strong>Places API (New)</strong> — {@code GET v1/places/{id}} with an
 * {@code X-Goog-FieldMask} — and normalizes the response to a {@link PlaceReviews}
 * (aggregate rating + rating count + up to 5 reviews). The API key never appears in logs
 * or URLs (sent as the {@code X-Goog-Api-Key} header).
 *
 * <p>Google's Place Details returns <strong>at most 5 reviews</strong> (no pagination) in
 * "most relevant" order — that's the ceiling for a public marketing site. The minimal field
 * mask keeps the call on the cheaper SKU and the payload small. If the place id / API key
 * is not yet configured, {@link #fetch()} is a no-op ({@code null}) so the scheduled refresh
 * stays quiet until the operator provides them (10.1 / 10.2).
 */
@Component
class GooglePlacesAdapter implements GooglePlacesClient {

    private static final Logger log = LoggerFactory.getLogger(GooglePlacesAdapter.class);
    private static final String FIELD_MASK = "id,displayName,rating,userRatingCount,reviews";
    private static final int MAX_REVIEWS = 5;

    private final RestClient placesRestClient;
    private final ReviewsProperties properties;

    GooglePlacesAdapter(@Qualifier("placesRestClient") RestClient placesRestClient, ReviewsProperties properties) {
        this.placesRestClient = placesRestClient;
        this.properties = properties;
    }

    @Override
    public PlaceReviews fetch() {
        if (!StringUtils.hasText(properties.apiKey()) || !StringUtils.hasText(properties.placeId())) {
            log.info("Google Places not configured (place-id / api-key) — reviews refresh is a no-op until set");
            return null;
        }
        PlaceResponse body = placesRestClient.get()
                .uri("/places/{id}", properties.placeId())
                .header("X-Goog-Api-Key", properties.apiKey())
                .header("X-Goog-FieldMask", FIELD_MASK)
                .retrieve()
                .body(PlaceResponse.class);
        if (body == null) {
            return null;
        }
        List<ApiReview> raw = body.reviews() == null ? List.of() : body.reviews();
        List<CachedReview> reviews = raw.stream().limit(MAX_REVIEWS).map(GooglePlacesAdapter::toCached).toList();
        return new PlaceReviews(roundRating(body.rating()), body.userRatingCount(), reviews);
    }

    /** Google's rating is a double (e.g. 4.7); the cache column is numeric(2,1). */
    private static BigDecimal roundRating(Double rating) {
        return rating == null ? null : BigDecimal.valueOf(rating).setScale(1, RoundingMode.HALF_UP);
    }

    private static CachedReview toCached(ApiReview r) {
        AuthorAttribution a = r.authorAttribution();
        String text = r.text() != null && r.text().text() != null
                ? r.text().text()
                : (r.originalText() != null ? r.originalText().text() : null);
        return new CachedReview(
                a != null ? a.displayName() : null,
                a != null ? a.uri() : null,
                a != null ? a.photoUri() : null,
                r.rating(),
                text,
                r.publishTime(),
                r.relativePublishTimeDescription());
    }

    // --- Places API (New) response subset (only the masked fields; ignore the rest). ---
    @JsonIgnoreProperties(ignoreUnknown = true)
    record PlaceResponse(Double rating, Integer userRatingCount, List<ApiReview> reviews) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record ApiReview(Integer rating, LocalizedText text, LocalizedText originalText,
                     String publishTime, String relativePublishTimeDescription,
                     AuthorAttribution authorAttribution) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record LocalizedText(String text, String languageCode) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record AuthorAttribution(String displayName, String uri, String photoUri) {
    }
}
