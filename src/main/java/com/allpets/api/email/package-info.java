/**
 * Transactional email (owned by <strong>Epic 13</strong>).
 *
 * <p>{@code EmailService} (Spring Mail / {@code JavaMailSender} over SMTP) is the
 * site-mail sender — phase 1 sends the contact-form staff notification. Templates,
 * From-address policy and the SMTP provider are wired in Epic 13; the contact flow
 * (20.3) calls this service after the submission is persisted.
 */
package com.allpets.api.email;
