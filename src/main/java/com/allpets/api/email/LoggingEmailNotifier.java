package com.allpets.api.email;

import com.allpets.api.contact.domain.ContactSubmission;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Phase-1 stub {@link EmailNotifier}: records that a notification <em>would</em> be sent,
 * logging only the submission id (never PII — LLD §10). Epic 13 replaces this with the real
 * SMTP sender (it can mark its bean {@code @Primary} or this one can be made conditional).
 */
@Component
public class LoggingEmailNotifier implements EmailNotifier {

    private static final Logger log = LoggerFactory.getLogger(LoggingEmailNotifier.class);

    @Override
    public void sendContactNotification(ContactSubmission submission) {
        log.info("contact notification pending (no mail provider until Epic 13) for submission id={}",
                submission.getId());
    }
}
