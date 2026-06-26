package com.allpets.api.reviews.client;

/**
 * Port for fetching the latest reviews + aggregate rating for the configured place.
 *
 * <p>Port-and-adapter, like {@code EmailNotifier} / {@code RateLimiter}: the real adapter
 * ({@code GooglePlacesAdapter}) calls the Google Places API; tests mock this interface so
 * the scheduled refresh runs without a live Google call.
 */
public interface GooglePlacesClient {

    /**
     * Fetch the current reviews for the configured place, or {@code null} if the data is
     * unavailable (not configured, or the upstream returned nothing). Throws on a transport
     * or HTTP error — {@code ReviewsService} treats both {@code null} and exceptions as
     * "keep the last-good cache".
     */
    PlaceReviews fetch();
}
