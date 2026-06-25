/**
 * Application configuration: CORS allowlist, security, JPA, mail, scheduling and
 * Actuator wiring.
 *
 * <p>Phase-1 status: the skeleton (20.1) keeps cross-cutting config in
 * {@code application.yml} (Actuator health groups, Prometheus, info). Concrete
 * {@code @Configuration} classes land with their feature:
 * <ul>
 *   <li>CORS allowlist ({@code https://allpets.skpodduturi.dev}, credentials off) — {@code CorsConfig} (20.4).</li>
 *   <li>JPA / datasource / Flyway — 20.2.</li>
 *   <li>Mail (Spring Mail / JavaMailSender) — Epic 13.</li>
 *   <li>{@code @Scheduled} reviews refresh — Epic 10.</li>
 * </ul>
 */
package com.allpets.api.config;
