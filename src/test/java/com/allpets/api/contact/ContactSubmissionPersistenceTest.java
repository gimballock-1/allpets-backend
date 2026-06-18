package com.allpets.api.contact;

import static org.assertj.core.api.Assertions.assertThat;

import com.allpets.api.contact.domain.ContactSubmission;
import com.allpets.api.contact.repository.ContactSubmissionRepository;
import com.allpets.api.support.PostgresIntegrationTest;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Verifies 20.2 end-to-end against a real Postgres: the Flyway schema applied (so
 * Hibernate {@code validate} passed at context load), both phase-1 tables exist, and a
 * {@link ContactSubmission} round-trips — including the DB-generated id and the
 * {@code created_at} default read back after insert.
 */
@SpringBootTest
class ContactSubmissionPersistenceTest extends PostgresIntegrationTest {

    @Autowired
    private ContactSubmissionRepository repository;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void flywayCreatedBothPhase1Tables() {
        assertThat(tableExists("contact_submissions")).isTrue();
        assertThat(tableExists("reviews_cache")).isTrue();
    }

    @Test
    void contactSubmissionRoundTrips() {
        ContactSubmission saved = repository.save(new ContactSubmission(
                "Jamie Vet", "Jamie@Example.com", "Is Dr. Lee taking new patients?",
                "203.0.113.7", "Mozilla/5.0"));   // exercises the inet binding

        assertThat(saved.getId()).isNotNull();
        assertThat(saved.getCreatedAt()).isNotNull();   // DB default now() read back

        Optional<ContactSubmission> found = repository.findById(saved.getId());
        assertThat(found).isPresent();
        assertThat(found.get().getMessage()).isEqualTo("Is Dr. Lee taking new patients?");
        assertThat(found.get().getSourceIp()).isEqualTo("203.0.113.7");
    }

    @Test
    void emailColumnIsCaseInsensitiveCitext() {
        // The column is genuinely citext (drives case-insensitive erasure-by-email, 14.10).
        String udtName = jdbc.queryForObject(
                "SELECT udt_name FROM information_schema.columns "
                        + "WHERE table_name = 'contact_submissions' AND column_name = 'email'",
                String.class);
        assertThat(udtName).isEqualTo("citext");

        repository.save(new ContactSubmission("Casey", "Casey@Example.com", "hello", null, null));
        // A lower-cased lookup matches the mixed-case stored value — but the bound JDBC
        // parameter is typed varchar, so it must be CAST to citext for the comparison to
        // use citext (case-insensitive) semantics. Erasure-by-email (14.10) must do the same.
        Integer matches = jdbc.queryForObject(
                "SELECT count(*) FROM contact_submissions WHERE email = CAST(? AS citext)",
                Integer.class, "casey@example.com");
        assertThat(matches).isEqualTo(1);
    }

    private boolean tableExists(String table) {
        Integer n = jdbc.queryForObject(
                "SELECT count(*) FROM information_schema.tables WHERE table_schema = 'public' AND table_name = ?",
                Integer.class, table);
        return n != null && n == 1;
    }
}
