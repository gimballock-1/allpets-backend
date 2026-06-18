/**
 * Contact-form domain layer: the {@code ContactSubmission} JPA entity mapping the
 * {@code contact_submissions} table. Entity + schema (Flyway {@code V1__init.sql})
 * land in 20.2. Follows the tenant-aware convention (stable surrogate PK, no
 * single-clinic assumption) without yet carrying a {@code tenant_id} column.
 */
package com.allpets.api.contact.domain;
