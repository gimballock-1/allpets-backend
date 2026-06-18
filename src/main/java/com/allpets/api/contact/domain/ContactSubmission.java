package com.allpets.api.contact.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.hibernate.annotations.Generated;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.generator.EventType;
import org.hibernate.type.SqlTypes;

/**
 * A public contact-form submission (the {@code contact_submissions} table, LLD §4.1).
 *
 * <p>Follows the tenant-aware convention — a stable surrogate UUID key, no single-clinic
 * assumption — without yet carrying a {@code tenant_id} column (phase-2 seed, §3.2/§12).
 * The schema is owned by Flyway ({@code V1__init.sql}); {@code ddl-auto=validate} keeps
 * this mapping and the live schema in lock-step.
 */
@Entity
@Table(name = "contact_submissions")
public class ContactSubmission {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "name", nullable = false)
    private String name;

    /** Case-insensitive ({@code citext}) for erasure-by-email lookups (14.10). */
    @Column(name = "email", nullable = false, columnDefinition = "citext")
    private String email;

    @Column(name = "message", nullable = false)
    private String message;

    /** Set by the database default {@code now()} and read back after insert. */
    @Generated(event = EventType.INSERT)
    @Column(name = "created_at", nullable = false, updatable = false, insertable = false)
    private OffsetDateTime createdAt;

    /**
     * Spam-triage only; never logged (no PII in logs — LLD §10). Nullable.
     * Mapped to the Postgres {@code inet} type so binding/casts are handled correctly.
     */
    @JdbcTypeCode(SqlTypes.INET)
    @Column(name = "source_ip", columnDefinition = "inet")
    private String sourceIp;

    /** Spam-triage only. Nullable. */
    @Column(name = "user_agent")
    private String userAgent;

    protected ContactSubmission() {
        // for JPA
    }

    public ContactSubmission(String name, String email, String message, String sourceIp, String userAgent) {
        this.name = name;
        this.email = email;
        this.message = message;
        this.sourceIp = sourceIp;
        this.userAgent = userAgent;
    }

    public UUID getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public String getEmail() {
        return email;
    }

    public String getMessage() {
        return message;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }

    public String getSourceIp() {
        return sourceIp;
    }

    public String getUserAgent() {
        return userAgent;
    }
}
