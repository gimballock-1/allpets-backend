/**
 * Shared web layer: the JSON error model, a {@code @RestControllerAdvice} that maps
 * validation/exception failures to a consistent response, and the per-IP rate-limit +
 * honeypot hooks for {@code POST /contact}. Owned by <strong>Epic 14</strong> (14.2/14.3/14.4);
 * the error advice may be introduced alongside 20.3.
 */
package com.allpets.api.common.web;
