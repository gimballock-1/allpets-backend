-- V1__init.sql — appdb phase-1 schema (Backend LLD §4.1/§4.2).
--
-- Owned by Flyway, forward-only, idempotent, never edited after merge. Runs as the
-- app_svc role (owner of appdb). Assumes the ADR-4.1 pre-created extensions exist
-- (pgcrypto -> gen_random_uuid(), citext) so the app role never needs CREATE EXTENSION.

-- contact_submissions — public-write inbox, persisted by POST /contact (20.3).
CREATE TABLE contact_submissions (
    id          uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    name        text        NOT NULL,
    email       citext      NOT NULL,
    message     text        NOT NULL,
    created_at  timestamptz NOT NULL DEFAULT now(),
    source_ip   inet,
    user_agent  text
);

-- Newest-first triage of the inbox.
CREATE INDEX idx_contact_submissions_created_at ON contact_submissions (created_at DESC);

-- reviews_cache — single current batch of Google reviews (id is fixed = 1).
-- Written by the @Scheduled refresh, read by GET /reviews. The JPA entity + endpoint
-- are added by Epic 10; the table is created here so V1 brings appdb fully up (LLD §4.2).
CREATE TABLE reviews_cache (
    id               smallint    PRIMARY KEY,
    aggregate_rating numeric(2,1),
    rating_count     integer,
    reviews          jsonb       NOT NULL DEFAULT '[]'::jsonb,
    fetched_at       timestamptz
);
