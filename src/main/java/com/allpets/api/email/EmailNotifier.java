package com.allpets.api.email;

import com.allpets.api.contact.domain.ContactSubmission;

/**
 * Port for sending the staff notification when a contact submission arrives.
 *
 * <p>Phase 1 ships a logging stub ({@link LoggingEmailNotifier}); <strong>Epic 13</strong>
 * supplies the real Spring Mail / {@code JavaMailSender} adapter (provider, templates,
 * verified From-address). Keeping this a port lets the contact flow (20.3) be complete and
 * testable now and lets Epic 13 swap the implementation without touching the service.
 */
public interface EmailNotifier {

    /** Send the staff notification for a persisted submission. Implementations must not throw on transient failure being acceptable — the caller treats failures as non-fatal. */
    void sendContactNotification(ContactSubmission submission);
}
