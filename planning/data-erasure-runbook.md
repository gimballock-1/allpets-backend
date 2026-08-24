# Data-subject erasure runbook + `contact_submissions` retention (14.10)

> **Owner:** 14.10. **Source of truth** for erasing one person's data from every
> store allpets holds it in, and for the automated retention purge. Personal data
> lives in **two primary stores plus one copy path**: (1) the Spring backend's
> `contact_submissions` table in **appdb** (name / email / message / source IP /
> user agent, req §5.2/§7.1); (2) **Cal.com hosted** (cal.com Teams SaaS —
> booking intake answers: owner name/email/phone, pet details, req §4.5.1); and
> (3) — **once Epic 13 lands** — the **clinic notification mailbox**, which
> receives an email copy of every accepted contact submission (name/email/
> message) via SMTP (`ContactService` → `EmailNotifier`, lld-backend.md §6; the
> phase-1 stub logs only the submission id, no PII). There is **no** self-hosted
> Cal.com database — the 2026-06-26 decision moved booking to Cal.com's cloud,
> so the booking half of this runbook is a **staff procedure in the hosted
> Cal.com admin UI**, not SQL. One erasure request = **all** applicable
> checklists below, always.
>
> **This runbook bounds what the privacy policy (17.9) may promise** — see §5.
> Deliberate design decision: there is **no HTTP erasure endpoint**. The API has
> no auth surface (public site + `/contact` only), so an admin endpoint would be
> an unauthenticated PII-deletion vector. The operator path below (`kubectl` +
> `psql`, tailnet-gated cluster access) *is* the tool (admin-surface-decision.md
> posture).

## 1. Erasure request — the checklist (run ALL of it, in order)

Trigger: a verified request from a data subject (or staff on their behalf) to
delete their personal data. Verify the requester controls the email address
(reply-to-confirm) **before** deleting — erasure is irreversible.

- [ ] Verify the requester's control of the target email address.
- [ ] §1.1 — delete their `contact_submissions` rows in appdb (preview → delete).
- [ ] §1.2 — cancel/remove their Cal.com bookings (hosted admin UI). If past
  bookings existed, a Cal.com support deletion request is now **pending** — the
  erasure is NOT complete until support confirms.
- [ ] §1.3 — delete their contact-notification emails from the clinic mailbox
  (applies once Epic 13's SMTP notifier is live; no-op before that).
- [ ] §1.4 — record the action (who/when/counts — **never** the PII itself).
- [ ] Reply to the requester. If everything above completed: done, plus the
  backup-lag caveat from §3 (their rows persist in local backup archives up to
  ~15 days, then rotate out). If a Cal.com support deletion is still pending:
  report the request as **in progress**, and send the completion reply only
  after support confirms (record the confirmation in the §1.4 log).

### 1.1 appdb: `contact_submissions`

Runs over the postgres pod's local socket as the app role `app_svc` (owner of
appdb — no superuser needed; password is already in the pod env from
`postgres-secret`, same pattern as deployment.md §3.5). `email` is `citext`, so
matching is case-insensitive — one exact-address delete catches case variants.
The address travels as a **positional argument** into the pod's `sh`, then as a
**psql variable** into the SQL (`:'target_email'` quotes it) — safe for any
address, quotes included; never paste the email into the SQL text itself.

```bash
TARGET_EMAIL='person@example.com'   # the verified address — exact, full match

# 1) PREVIEW — how many rows will go (sanity check before the delete):
kubectl -n allpets-database exec -i deploy/postgres -- \
  sh -c 'PGPASSWORD="$APP_SVC_PASSWORD" psql -X -U app_svc -d appdb -v ON_ERROR_STOP=1 -v target_email="$1"' sh "${TARGET_EMAIL}" <<'SQL'
SELECT count(*) AS matching_rows FROM contact_submissions WHERE email = :'target_email';
SQL

# 2) DELETE — same predicate; psql prints `DELETE <n>`; n must equal the preview:
kubectl -n allpets-database exec -i deploy/postgres -- \
  sh -c 'PGPASSWORD="$APP_SVC_PASSWORD" psql -X -U app_svc -d appdb -v ON_ERROR_STOP=1 -v target_email="$1"' sh "${TARGET_EMAIL}" <<'SQL'
DELETE FROM contact_submissions WHERE email = :'target_email';
SQL
```

Idempotent: re-running the delete prints `DELETE 0`. A preview of `0` is a valid
outcome (nothing stored, or already purged by retention §2) — still do §1.2–§1.4.

### 1.2 Cal.com (hosted SaaS) — staff procedure

> **⚠️ VERIFY AT EPIC 9 ONBOARDING.** The clinic has not signed up yet; these
> steps are written against Cal.com's current hosted product docs and must be
> walked through and corrected (if the UI has moved) when the Teams account is
> created. Until then treat this subsection as a draft procedure.

There is no SQL path — booking data lives in Cal.com's cloud. As a clinic admin
on app.cal.com:

1. **Bookings** → search/filter by the attendee's email (Upcoming, Unconfirmed,
   Recurring, Past, Canceled tabs — check them all).
2. **Upcoming/unconfirmed bookings: Cancel** each one. **Cancellation propagates
   to the synced Google Calendar event** on the vet's calendar (per-vet OAuth
   connection) — the event is removed/updated there automatically. Expect the
   attendee to receive a cancellation email; tell the requester to expect it.
3. **Past bookings:** the admin UI shows but does not hard-delete them, and the
   attendee's intake answers (owner name/email/phone, pet details) remain in
   Cal.com's store. For actual erasure of past-booking PII, file a deletion
   request with Cal.com support (support@cal.com; they are the processor —
   GDPR/DPA-backed on paid plans). Record the ticket ID in the §1.4 log entry,
   **track it to confirmation, and record the confirmation** — until then the
   erasure request stays open/in-progress (see the §1 checklist's reply step).
4. **Google Calendar residue:** cancellation only cleans up events Cal.com
   created and still tracks. Spot-check the vet's Google Calendar for the
   attendee's past events — a copied/edited event, or one from before a
   reconnect, is clinic-owned and must be deleted by hand in Google Calendar.

### 1.3 Clinic notification mailbox (applies once Epic 13 is live)

From Epic 13 on, every accepted contact submission is also **emailed to the
clinic mailbox** (name/email/message — lld-backend.md §6). Those copies are
outside appdb, so neither §1.1 nor the §2 retention purge touches them:

1. In the clinic mailbox, search for contact-form notification emails
   containing the target address (search the To/body for `TARGET_EMAIL`).
2. Delete each hit **and empty it from the mailbox trash** (a trashed email is
   not erased). If the mailbox provider retains deleted mail server-side,
   that retention window belongs in §5's promises — pin it down at Epic 13.
3. Count the deletions for the §1.4 log entry.

Before Epic 13 this is a **no-op**: the phase-1 `LoggingEmailNotifier` stub
logs only the submission id (no PII). Revisit this section when Epic 13 picks
its mail provider — and prefer keeping PII *out* of the notification body
(send a submission id/reference instead), which would shrink this store away.

### 1.4 Log the action (never the PII)

Record — in the ops log (private GitHub issue comment thread on the erasure
request, or the clinic's internal log), **not** in anything public and **never**
including the email/name/message content:

```
2026-08-24 · erasure · operator: <who> · appdb rows deleted: <n> ·
calcom bookings cancelled: <n> · calcom support ticket: <id or n/a> ·
calcom deletion confirmed: <date or PENDING> · mailbox copies deleted: <n or n/a> ·
requester verified: yes · completion reply sent: <date or pending calcom>
```

This mirrors the req §8.4 rule the purge job follows: log actions and counts,
never the data.

## 2. Automated retention purge (`contact-retention-purge` CronJob)

Manifest: `deploy/k8s/api/retention-purge-cronjob.yaml` (ns `allpets-backend`,
wired into `deploy/k8s/api/kustomization.yaml` — applied by CD / `apply -k`).

- **What:** nightly `DELETE FROM contact_submissions WHERE created_at < now() -
  <RETENTION_DAYS> days`, as `app_svc` over
  `postgres.allpets-database.svc:5432` (password: `allpets-api-secret` /
  `SPRING_DATASOURCE_PASSWORD` — the API's own credential, no new secret).
  Idempotent; logs **only** the deleted count.
- **Window:** `RETENTION_DAYS=180` — **recommended default, PENDING client
  confirmation (18.14)**. To change it: edit that one env var, then update this
  file + the 17.9 privacy text to match.
- **Schedule:** `30 4 * * *` (cluster time) — after the 03:00 `pgdump-nightly`
  worst case (03:00 + `startingDeadlineSeconds` 3600 ⇒ a delayed dump can start
  as late as ~04:00), so purge and backup never contend and each night's dump is
  taken *before* that night's purge.
- **NetworkPolicy:** covered as-is — `allow-from-backend` (allpets-database ns)
  admits any pod in the `allpets-backend` **namespace** on 5432; no new policy
  (contrast §3.4 of deployment.md, which was about *in-namespace* jobs).
- **Run it manually / smoke-test** (first deploy, or after changing the window):

```bash
kubectl -n allpets-backend create job --from=cronjob/contact-retention-purge purge-manual-verify
kubectl -n allpets-backend wait --for=condition=complete job/purge-manual-verify --timeout=120s
kubectl -n allpets-backend logs job/purge-manual-verify   # expect: "purged N rows"
kubectl -n allpets-backend delete job purge-manual-verify
```

## 3. Backups caveat — erasure is NOT instant-everywhere (be honest about this)

The nightly `pgdump-nightly` CronJob (deployment.md §3.6) dumps appdb at
**03:00** to the local `pgdump-pvc` and prunes archives **older than 14 days**
(`find -mtime +14`, evaluated once per night). Consequences:

- A row erased (§1.1) or purged (§2) today still exists in every dump taken
  while it lived — newest such archive: this morning's 03:00 dump.
- That archive becomes prune-eligible when >14 days old and is deleted at the
  next 03:00 run ⇒ **erased/purged rows persist in on-disk backup archives for
  up to ~15 days after deletion** (14-day retention + nightly prune
  granularity), then are gone. No off-site copies exist (§3.7) — local PVC only.
- **Do not manually restore a dump** (§3.8) without re-running §1.1 for every
  erasure performed since that dump was taken — a restore silently resurrects
  erased rows. Check the ops log (§1.4) after any restore.
- Cal.com hosted keeps its own backups on its own schedule — outside our
  control; their deletion timeline is governed by their DPA (§1.2 step 3).

## 4. Local verification (how this was tested — no prod access)

Verified 2026-08-24 against a throwaway `postgres:16.4` docker container with the
exact V1 `contact_submissions` DDL (citext) and seeded rows: 2 rows for the
erasure target (mixed case), 1 row aged 200 days, 1 unrelated "honey" row.
The CronJob's rendered script purged exactly 1 row, re-ran to 0; the §1.1
erasure previewed 2 (citext matched both case variants), deleted 2, re-ran to 0;
the honey row survived everything. Transcript in PR (refs #130). On-cluster
verification of the CronJob itself: run the §2 smoke-test after merge.

## 5. What 17.9 (privacy policy) may promise — and no more

- **Stores covered:** the contact-form inbox (`contact_submissions` in appdb),
  Cal.com hosted booking data, and — once Epic 13 ships SMTP notifications —
  the clinic notification mailbox (§1.3; its provider-side retention must be
  pinned down at Epic 13 before 17.9 promises anything about it). Nothing else
  holds personal data in phase 1.
- **Retention:** contact-form submissions are deleted automatically after
  **180 days** (pending 18.14 — keep policy text in sync with the CronJob's
  `RETENTION_DAYS`).
- **Erasure on request:** manual operator procedure (§1) — promise a response
  window (e.g. "within 30 days"), not immediacy.
- **Backup lag:** deleted data may persist in local backup archives for **up to
  ~15 days** before rotating out. Do **not** promise "immediate and total"
  deletion.
- **Processor:** booking data is processed by Cal.com (hosted); its erasure and
  backup timelines follow Cal.com's DPA. Name them as a processor.
