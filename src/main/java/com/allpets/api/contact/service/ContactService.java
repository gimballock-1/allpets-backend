package com.allpets.api.contact.service;

import com.allpets.api.contact.domain.ContactSubmission;
import com.allpets.api.contact.repository.ContactSubmissionRepository;
import com.allpets.api.email.EmailNotifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Owns the contact-submission transaction: persist, then trigger the staff notification.
 * The email is sent <em>after</em> the row is persisted so a transient mail failure never
 * loses the submission (it is logged, never surfaced as a 5xx — LLD §6). No PII is logged.
 */
@Service
public class ContactService {

    private static final Logger log = LoggerFactory.getLogger(ContactService.class);

    private final ContactSubmissionRepository repository;
    private final EmailNotifier emailNotifier;

    public ContactService(ContactSubmissionRepository repository, EmailNotifier emailNotifier) {
        this.repository = repository;
        this.emailNotifier = emailNotifier;
    }

    @Transactional
    public void submit(String name, String email, String message, String sourceIp, String userAgent) {
        ContactSubmission saved = repository.save(
                new ContactSubmission(name, email, message, sourceIp, userAgent));
        log.info("contact submission persisted id={}", saved.getId());

        // Non-fatal: a send failure must not roll back / lose the persisted submission.
        // (Epic 13 may move this to an AFTER_COMMIT listener with the real SMTP sender.)
        try {
            emailNotifier.sendContactNotification(saved);
        } catch (RuntimeException e) {
            log.error("contact notification failed for submission id={} (submission is safely persisted)",
                    saved.getId(), e);
        }
    }
}
