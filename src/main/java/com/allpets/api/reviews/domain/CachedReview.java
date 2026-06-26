package com.allpets.api.reviews.domain;

/**
 * One normalized Google review as stored in the {@code reviews_cache.reviews} jsonb array
 * and served by {@code GET /reviews}.
 *
 * <p>The attribution fields ({@code author}, {@code authorUri}, {@code authorPhotoUri}) are
 * retained because Google's Places policies require the consuming UI to display the
 * reviewer's name (and the "Google" attribution) — the cache must keep them so the
 * frontend (8.5) can satisfy that. {@code relativeTime} is Google's pre-formatted string
 * (e.g. "2 weeks ago") shown as-is; {@code time} is the absolute publish time as an ISO-8601
 * string (kept as text so the jsonb serializer needs no Java-time module).
 */
public record CachedReview(
        String author,
        String authorUri,
        String authorPhotoUri,
        Integer rating,
        String text,
        String time,
        String relativeTime) {
}
