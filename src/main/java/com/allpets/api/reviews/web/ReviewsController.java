package com.allpets.api.reviews.web;

import com.allpets.api.reviews.service.ReviewsService;
import java.time.Duration;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code GET /reviews} — public, unauthenticated, read-only. Serves the cached batch only
 * (never a live Google call on the request path); the site's same-origin {@code /api/reviews}
 * proxy is the caller (Frontend 8.5). CORS is enforced app-side (20.4) to the site origin.
 *
 * <p>A cold/empty cache yields a typed-empty 200 (not 5xx). {@code Cache-Control: public,
 * max-age=300} matches the 5-minute frontend TTL.
 */
@RestController
public class ReviewsController {

    private final ReviewsService reviewsService;

    public ReviewsController(ReviewsService reviewsService) {
        this.reviewsService = reviewsService;
    }

    @GetMapping("/reviews")
    public ResponseEntity<ReviewsResponse> reviews() {
        ReviewsResponse body = reviewsService.currentCache()
                .map(ReviewsResponse::from)
                .orElseGet(ReviewsResponse::empty);
        return ResponseEntity.ok()
                .cacheControl(CacheControl.maxAge(Duration.ofMinutes(5)).cachePublic())
                .body(body);
    }
}
