/**
 * Google-reviews cache bounded context (owned by <strong>Epic 10</strong>).
 *
 * <p>{@code GET /reviews} serves the cached batch only — never a live Google call on
 * the request path. A Spring {@code @Scheduled} task in {@code ReviewsService} refreshes
 * the cache (24h cadence) via the Places API and atomically swaps the single
 * {@code reviews_cache} row. Web · service · domain · repository layers are added by
 * Epic 10 (10.x); this package marks the structural home.
 */
package com.allpets.api.reviews;
