/**
 * allpets backend API — a single deployable Spring Boot module, layered by
 * responsibility and organized <strong>domain-first</strong> so phase-2 bounded
 * contexts (pet/patient profiles, multi-branch multi-tenancy) slot in cleanly.
 *
 * <p>Module map (Backend LLD §3.2):
 * <ul>
 *   <li>{@link com.allpets.api.config} — CORS, security, JPA, mail, scheduling, Actuator wiring.</li>
 *   <li>{@link com.allpets.api.contact} — the contact form (web · service · domain · repository). Epic 20.</li>
 *   <li>{@code com.allpets.api.reviews} — Google-reviews cache. Added by Epic 10.</li>
 *   <li>{@link com.allpets.api.email} — transactional email (Spring Mail). Wired by Epic 13.</li>
 *   <li>{@link com.allpets.api.common} — cross-cutting: tenant-aware seed (phase-2) and the web error/rate-limit model (Epic 14).</li>
 * </ul>
 *
 * <p>Clean-layering rule: {@code web → service → repository → domain}. Controllers do
 * no persistence; services own transactions; repositories are Spring Data interfaces.
 * Cross-domain calls go service-to-service, never controller-to-repository.
 */
package com.allpets.api;
