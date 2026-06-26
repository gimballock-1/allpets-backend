package com.allpets.api.reviews;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Reviews config (env-overridable, {@code allpets.reviews.*} in {@code application.yml}).
 *
 * <ul>
 *   <li>{@code placeId} — the clinic's Google Place ID (NON-secret, {@code GOOGLE_PLACE_ID}; 10.1).</li>
 *   <li>{@code apiKey} — the Places API key (SECRET, {@code GOOGLE_PLACES_API_KEY}; 10.2).</li>
 *   <li>{@code refreshCron} / {@code timezone} — the daily refresh schedule.</li>
 * </ul>
 * Both {@code placeId} and {@code apiKey} are empty until the operator provides them; while
 * empty the refresh is a no-op and {@code GET /reviews} serves a typed-empty payload.
 */
@ConfigurationProperties(prefix = "allpets.reviews")
public record ReviewsProperties(String placeId, String apiKey, String refreshCron, String timezone) {
}
