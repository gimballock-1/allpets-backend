package com.allpets.api.contact.repository;

import com.allpets.api.contact.domain.ContactSubmission;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Spring Data JPA repository over {@link ContactSubmission}. The write path
 * ({@code POST /contact}) and any erasure/purge queries (14.10) build on this.
 */
public interface ContactSubmissionRepository extends JpaRepository<ContactSubmission, UUID> {
}
