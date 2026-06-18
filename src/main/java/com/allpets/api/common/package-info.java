/**
 * Cross-cutting concerns shared across bounded contexts.
 *
 * <ul>
 *   <li>{@link com.allpets.api.common.tenant} — tenant-aware base convention, a
 *       <strong>phase-2 seed only</strong> (no {@code tenant_id} column is created in phase 1).</li>
 *   <li>{@link com.allpets.api.common.web} — shared web error model,
 *       {@code @RestControllerAdvice}, and the rate-limit/honeypot hooks (Epic 14).</li>
 * </ul>
 */
package com.allpets.api.common;
