/**
 * Contact-form bounded context: the one phase-1 write path.
 *
 * <p>The site's same-origin {@code /api/contact} proxy posts to {@code POST /contact};
 * the request is bean-validated, persisted as a {@code ContactSubmission}, then a
 * staff-notification email is triggered. Honeypot + per-IP rate-limit hooks apply here.
 *
 * <p>Layered: {@link com.allpets.api.contact.web} → {@link com.allpets.api.contact.service}
 * → {@link com.allpets.api.contact.repository} over {@link com.allpets.api.contact.domain}.
 * The controller/service/repository land in 20.3; the entity + schema in 20.2.
 */
package com.allpets.api.contact;
