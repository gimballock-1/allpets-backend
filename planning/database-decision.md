# ADR 4.1 — Database & object-storage shape: plain Postgres (not CloudNativePG) + local-only backup (no off-site)

> **Type:** Recorded decision (Epic 4 · 4.1). This file is the *output* — a decision, not infrastructure. The chosen shapes are *implemented* by the sibling build issues: Postgres workload **4.2**, roles/DBs **4.3**, backup CronJob **4.5**, MinIO **4.6/4.7/4.8**. Manifests land under `deploy/k8s/database/`. No manifests are authored here.
>
> **Status:** Accepted — 2026-06-15. **Owner-decided** (these two calls were made by the project owner on 2026-06-15 and *override the Epic-04 spec where they differ*).
>
> **Scope:** the data tier in namespace `allpets-database` on the single-node k3s box `quasar`:
> - **Postgres** — the relational store backing Payload CMS and Cal.com.
> - **Backups** — how (and whether) that Postgres is backed up.
> - **MinIO** — S3-compatible object storage for Payload media (recorded here for completeness; its *shape* was not contested and is unchanged from the spec).

---

## Decision (summary)

| # | Question | **Decision** | One-line rationale |
|---|---|---|---|
| 1 | Postgres topology | **Plain Kubernetes `Deployment` + PVC + `Service`** — **NOT** CloudNativePG | Solo non-expert operator; parity with the proven aarogya shape on this exact box; do not introduce a cluster-wide operator on shared prod. |
| 2 | Backups | **Nightly `pg_dump` CronJob (4.5) to a *local* PVC — the primary and ONLY backup.** **No off-site backup; Backblaze B2 (4.4 / 1.9) is DROPPED.** | Owner-accepted trade-off: same-box backups, no PITR, in exchange for zero external dependency/credential/cost. |
| 3 | Object storage (media) | **MinIO `StatefulSet`, standalone (1 replica), local-path PVC** — unchanged from spec (4.6/4.7/4.8). | Distributed MinIO needs ≥4 drives (phase-2); standalone is the correct single-node shape. |

**The two contested calls are #1 and #2.** #3 is recorded for a complete data-tier picture but was not a point of contention.

Both #1 and #2 follow the same governing principle as the rest of phase 1: **mirror what already runs on this box, minimise net-new operational surface, and push complexity to the application/owner-accepted-risk layer rather than to new infrastructure.**

---

## Context (verified facts that frame these decisions)

The Epic-04 spec text predates a 2026-06-15 owner review + cluster verification; where they differ, the facts/decisions below win.

1. **Single-node k3s on `quasar`, shared with healthcare prod.** allpets is a co-tenant on one ~31 GiB / 16-vCPU node alongside **aarogya** (healthcare prod), `local-ai-proxy`, `home-assistant`, etc. Anything cluster-wide (an operator, a CRD, a webhook) is shared blast-radius for *those* tenants too, not just allpets.
2. **Storage is `local-path` only.** The only StorageClass is `local-path` (default; provisioner `rancher.io/local-path`; `WaitForFirstConsumer`; **no volume expansion**, no ZFS). It is **node-pinned** — a PVC binds to this node's disk. Fine on a single node; a documented caveat for any phase-2 multi-node move.
3. **Namespace `allpets-database` exists (2.5) and is empty.**
4. **NetworkPolicy already in place (2.6).** `deploy/k8s/networkpolicies/backend-database.yaml` already allows `allpets-backend → allpets-database` on **5432** (Postgres) and **9000** (MinIO) (and 8123 for a later ClickHouse). **No new NetworkPolicy is needed** — the workloads must simply keep Postgres on 5432 and MinIO on 9000 so the existing allow matches.
5. **Co-tenant resource budget is enforced (2.12).** `allpets-database` carries a `ResourceQuota` (`requests 2 cpu / 8Gi`, `limits 6 cpu / 12Gi`) and a `LimitRange` (defaults `req 100m/256Mi`, `default-limit 250m/512Mi`, container `max 4cpu/8Gi`). **Every pod here MUST set requests/limits within that quota.**
6. **The 14.6 secrets pipeline does not exist yet.** There is no GitHub-repo-secret → k8s-Secret materialization path today. Therefore the build issues commit **Secret *templates* only** (`*.example.yaml`, placeholder `stringData`); the orchestrator creates the real Secrets out-of-band on the cluster with generated strong values; **14.6 will own materialization later.**

### The aarogya reference — what we copy, and what we deliberately do NOT

aarogya runs Postgres on this same box and is the **familiar shape** the owner already operates. We copy its *ergonomics* and **fix its bugs**.

**Copy (familiar, good):** `image: postgres:16`; init SQL via a **ConfigMap** mounted at `/docker-entrypoint-initdb.d`; credentials in a **k8s `Secret`** (e.g. `postgres-secret`, consumed via `envFrom`); a **plain `Deployment`**; a `Service` on **5432**.

**Do NOT copy (aarogya is a *dev* box on `dev.kinvee.in`; allpets is the *live clinic site*):**

| aarogya anti-pattern | Why it is wrong for allpets | allpets does instead |
|---|---|---|
| `pgdata` on an **`emptyDir`** | **Ephemeral — data is lost on every pod restart.** Unacceptable for live clinic data. | **A real `local-path` PVC**, ReadWriteOnce, ~10–20Gi (4.2). |
| DB exposed as a **`NodePort`** | Puts the database on a node port — needless exposure on a shared box. | **`ClusterIP` only** (internal); the 2.6 NetworkPolicy further restricts to `allpets-backend`. |
| **No resource requests/limits** | A spike could OOM-kill a neighbor (incl. healthcare prod). | **Requests/limits set per the 2.12 budget** on every pod. |

Recording these explicitly so a future reviewer who "helpfully" diffs against aarogya does not re-introduce an `emptyDir` or a `NodePort` thinking they restore parity. **Parity is intentional only for the *shape*, not for these three bugs.**

---

## Decision 1 — Plain Postgres `Deployment`, NOT CloudNativePG

### Options considered

**(a) Plain `Deployment` + PVC + `Service` — CHOSEN.**
A single Postgres container managed by an ordinary `Deployment`, data on a `local-path` PVC, reached over a `ClusterIP` `Service`. Roles/DBs created by an initdb ConfigMap (first boot) plus an idempotent one-shot Job (already-running clusters) — see 4.3.

- **Pros:** zero net-new cluster-wide machinery; identical to the shape the solo operator already runs (aarogya) so there is nothing new to learn to operate, debug, or upgrade; no operator/CRD/webhook added to a node that also runs healthcare prod; trivial mental model for backup/restore (it's just a Postgres container + a PVC + `pg_dump`); upgrades are an image-tag bump under owner control. Fits a single node perfectly.
- **Cons:** **no built-in HA/failover, no automated PITR, no operator-managed backups or in-place minor-version orchestration.** A pod/node loss means downtime until reschedule; recovery point is bounded by the nightly dump (Decision 2). **Mitigation:** single-node has no failover target *anyway* (HA is meaningless with one node); the recovery story is the nightly `pg_dump` (4.5); strategy `Recreate` + RWO PVC guarantees no split-brain on the volume.

**(b) CloudNativePG (CNPG) operator — REJECTED.**
Run Postgres via the CNPG operator (`Cluster` CRD), which brings managed failover, continuous archiving/PITR, and backup integration.

- **Pros:** managed HA/failover, continuous WAL archiving + PITR, declarative backups, smoother minor-version upgrades — genuinely better Postgres operations *at scale or with an expert operator*.
- **Cons (decisive):** (1) **It is cluster-wide infrastructure on a shared prod box.** Installing CNPG adds an operator Deployment, several CRDs, and a mutating/validating webhook to the node that also runs **healthcare prod** — net-new surface and a shared failure mode the *other* tenants inherit. (2) **Operator complexity for a solo non-expert operator.** CNPG's value (failover, PITR, backup orchestration) is exactly the surface area that must be *understood and operated correctly*; for a one-person phase-1 build its day-2 cost (CRD upgrades, webhook health, reconcile debugging) outweighs its benefit. (3) **Its headline features don't pay off here yet:** failover needs ≥2 nodes (we have one); PITR needs an archive target — and the owner has **explicitly dropped off-site backup** (Decision 2), so there is nowhere durable to archive WAL to anyway. (4) **No parity** with anything already on the box. The owner would be operating two different Postgres paradigms (plain aarogya + CNPG allpets) for no phase-1 gain.

**Decision: (a) plain `Deployment`.** CNPG is the right tool for a multi-node cluster with an operator-comfortable team and a durable archive target — none of which is true in phase 1. Re-evaluate at the phase-2 multi-node migration (Epic 1), not before.

### What this commits 4.2 to (recorded so the build has no guesswork)

- **Workload:** `Deployment`, **1 replica**, `strategy: Recreate` (RWO PVC — never two pods on one volume). Image **pinned to a specific minor** (`postgres:16.4` or current 16.x; **digest preferred** per 14.8 — never bare `postgres:16`). `envFrom` the bootstrap `Secret`. `PGDATA=/var/lib/postgresql/data/pgdata` with the PVC mounted at `/var/lib/postgresql/data` (data in a **subdir** so `lost+found` doesn't break initdb). **readiness + liveness via `pg_isready`** (exec). Requests/limits within budget.
- **Storage:** PVC, `local-path`, **ReadWriteOnce**, ~10–20Gi.
- **Service:** **`ClusterIP`** named `postgres`, port **5432**. In-cluster DSN apps use: `postgres.allpets-database.svc.cluster.local:5432`.
- **Roles/DBs (4.3):** two DBs `payload` (owner `payload_app`) and `calcom` (owner `calcom_app`); each role `LOGIN`, owns + full DDL on its **own** DB only; `REVOKE CONNECT` on the *other* DB; `REVOKE CONNECT … FROM PUBLIC` on both. Passwords from env (the Secret), **never hardcoded**. Applied two ways: an initdb shell script in a ConfigMap (first boot, `psql -v ON_ERROR_STOP=1`) **and** an idempotent one-shot Job (`DO $$` IF-NOT-EXISTS blocks; safe to re-run on a live cluster). Isolation intent: `psql` as `payload_app` into `calcom` must be **denied**.

### Trade-offs accepted (Decision 1)

- **No HA/failover.** Accepted — single node has no failover target; HA is deferred to the phase-2 multi-node move.
- **No automated PITR / continuous archiving.** Accepted — recovery point is the last nightly dump (see Decision 2 for the explicit RPO).
- **`local-path` is node-pinned, non-expandable.** Accepted for single-node; size the PVC with headroom up front (no online expansion). Flagged as a phase-2 migration caveat.

---

## Decision 2 — Local-only backup; NO off-site (Backblaze dropped)

### What changed vs the spec

The Epic-04 spec carried an off-site backup story (Backblaze B2, issues **4.4 / 1.9**). **The owner has dropped it.** The **entire** backup story is now the **nightly `pg_dump` CronJob (4.5) writing to a local PVC** — the **primary and only** backup. 4.4 / 1.9 (Backblaze B2) are **out of scope** for phase 1.

### Options considered

**(a) Local-only nightly `pg_dump` to a `local-path` PVC — CHOSEN.**

- **Pros:** zero external dependency, credential, or recurring cost; nothing to authenticate to or rotate; trivial to reason about and restore (`pg_restore` from a `.dump` file on a known PVC); fully self-contained on the box the owner already controls.
- **Cons:** **same-box durability only.** The backup PVC lives on the *same node's disk* as the live database — **disk loss = both the database and its backups are lost.** No protection against node/disk failure, theft, fire, or ransomware that reaches the node. RPO is bounded by the nightly cadence (up to ~24h of writes can be lost); no PITR.

**(b) Local nightly dump + off-site copy to Backblaze B2 (the original spec) — REJECTED by owner.**

- **Pros:** real off-site durability — survives total loss of the box; a genuinely independent recovery copy.
- **Cons / why rejected:** introduces an external dependency, a B2 bucket + credentials to provision/store/rotate, an egress/storage cost line, and net-new failure modes (auth expiry, upload failures to monitor) — for a phase-1, solo-operator, low-RPO-sensitivity clinic site. **Owner-accepted** that the on-box copy is sufficient for now and that the residual risk (below) is tolerable.

**Decision: (a) local-only.** This is an explicit, owner-made risk acceptance, recorded here so it is never mistaken for an oversight ("why is there no off-site backup?" → *because of this ADR*).

### What this commits 4.5 to

- **`CronJob`**, schedule `0 3 * * *` (nightly 03:00). Image: a **`pg_dump` client matching the server minor** (the pinned `postgres:16.x`).
- For **each** of `payload` + `calcom`: `pg_dump --format=custom` → `/backups/<db>-<date>.dump`.
- **Rotation in the same job:** prune `*.dump` older than 14 days (`find … -mtime +14 -delete`).
- Creds from a `Secret` (a connect-capable role — the app role is fine, connecting per-DB).
- `concurrencyPolicy: Forbid`; `successfulJobsHistoryLimit` / `failedJobsHistoryLimit` set; `restartPolicy: OnFailure`; requests/limits within budget.
- **PVC `pgdump-pvc`**, `local-path`, ~10Gi, mounted at `/backups`.

### Trade-offs accepted (Decision 2) — explicit risk register

| Risk | Status |
|---|---|
| **Disk/node loss destroys both DB *and* backups** (same-box copy) | **Accepted** (owner, 2026-06-15). Phase-1 tolerated; phase-2 maps `/backups` to a NAS / off-box target. |
| **No PITR** — recovery point is the last nightly dump (up to ~24h data loss) | **Accepted.** Clinic data is low-churn for phase 1; ~24h RPO judged tolerable. |
| No protection vs theft/fire/ransomware reaching the node | **Accepted** for phase 1; revisit if the threat model or compliance bar changes. |

---

## Decision 3 — MinIO object storage (recorded; unchanged from spec)

Not contested; recorded for a complete data-tier picture. MinIO backs **Payload media**.

- **4.6 — workload:** `StatefulSet`, **1 replica, standalone** (distributed MinIO needs ≥4 drives — phase-2). Image **pinned** (`quay.io/minio/minio:RELEASE.<pinned-date>`, 14.8). Args `server /data --console-address ":9001"`. `volumeClaimTemplate` PVC ~10Gi `local-path` at `/data`. `envFrom` a MinIO **root** `Secret`. `readinessProbe` `httpGet /minio/health/ready` on **9000**. Requests/limits within budget.
- **Service:** `ClusterIP` `minio`, ports **9000** (S3 API) + **9001** (console). Payload endpoint (5.10): `http://minio.allpets-database.svc.cluster.local:9000` (`forcePathStyle: true`). **Console (9001) is NOT exposed publicly** — tailnet / port-forward only (3.6 admin posture).
- **4.7 — setup Job** (pinned `minio/mc` image, one-shot): set `mc alias` to root creds → `mc mb --ignore-existing` bucket **`allpets-media`** → author a **scoped** policy JSON (`s3:GetObject/PutObject/DeleteObject` on `arn:aws:s3:::allpets-media/*` + `s3:ListBucket` on `arn:aws:s3:::allpets-media`) → `mc admin policy create` → `mc admin user add` the **scoped** payload key/secret (from the scoped-key Secret, **never root**) → `mc admin policy attach`. **Bucket stays PRIVATE** — Payload serves/proxies media (record for 5.10 / 7.7); **no anonymous download.**
- **4.8 (P2) — ILM:** `mc ilm rule add --expire-days 30 --prefix tmp/ local/allpets-media` (in the setup Job or a separate step). Prefix-scoped so it **must not** match normal media keys.

---

## Image pinning (14.8)

All data-tier images are **version-pinned, digest preferred** — never `latest`, never a bare major:

- Postgres (4.2 workload **and** the 4.5 `pg_dump` client): a specific 16.x minor (e.g. `postgres:16.4`), digest where practical; the dump client minor must match the server.
- MinIO (4.6): `quay.io/minio/minio:RELEASE.<pinned-date>`.
- `mc` (4.7): a pinned `minio/mc` release.

---

## Secrets posture (14.6 not built yet)

The 14.6 GitHub-secret → k8s-Secret pipeline **does not exist yet**, so the build issues commit **`*.example.yaml` TEMPLATES ONLY** — placeholder `stringData` (e.g. `PASSWORD: REPLACE_ME`), **never real secrets**. The orchestrator materialises the real Secrets out-of-band on the cluster with generated strong values. **14.6 owns materialization/rotation later.** Secret names the apps consume (keep **stable** so 14.6 can slot in without renaming):

- **Postgres bootstrap** Secret (consumed `envFrom`): `POSTGRES_USER` / `POSTGRES_PASSWORD` / `POSTGRES_DB`, plus the `payload_app` / `calcom_app` role passwords referenced by 4.3 (`$PAYLOAD_APP_PASSWORD` / `$CALCOM_APP_PASSWORD`).
- **MinIO root** Secret: `MINIO_ROOT_USER` / `MINIO_ROOT_PASSWORD`.
- **MinIO payload scoped-key** Secret: access key + secret (the **scoped** user 4.7 creates — never root).

---

## Footprint vs the 2.12 budget

`allpets-database` ResourceQuota: **requests `2 cpu / 8Gi`**, **limits `6 cpu / 12Gi`** (LimitRange supplies defaults). Planned per-pod allocation (sum stays under quota):

| Pod | Requests | Limits |
|---|---|---|
| Postgres (4.2) | 500m / 1Gi | 2 cpu / 4Gi |
| MinIO (4.6) | 250m / 512Mi | 1 cpu / 2Gi |
| `pg_dump` job (4.5) | 100m / 256Mi | 500m / 1Gi |
| **Sum (concurrent worst case)** | **~850m / ~1.75Gi** | **~3.5 cpu / ~7Gi** |
| **Quota** | 2 cpu / 8Gi | 6 cpu / 12Gi |
| **Headroom** | ✅ ~1.15 cpu / ~6.25Gi | ✅ ~2.5 cpu / ~5Gi |

Comfortably inside quota even with the nightly dump Job overlapping the long-running Postgres + MinIO pods. The `pg_dump` Job also runs at 03:00 (low-traffic), so practical contention is lower still. Each pod's per-container limit (Postgres 2cpu/4Gi, MinIO 1cpu/2Gi, pg_dump 500m/1Gi) also sits at or under the LimitRange container max of 4cpu/8Gi. A later ClickHouse (Plausible) co-tenant must be sized against the *remaining* headroom — flag at that epic.

---

## Relationship to sibling Epic-4 issues (so they don't conflict)

- **4.2 (Postgres workload)** — implements Decision 1's workload/PVC/Service shape above.
- **4.3 (roles/DBs + isolation)** — initdb ConfigMap + idempotent Job; per-DB ownership and cross-DB `REVOKE CONNECT`; passwords from the bootstrap Secret.
- **4.4 / 1.9 (Backblaze B2 off-site backup)** — **DROPPED** by Decision 2. Out of scope phase 1.
- **4.5 (nightly `pg_dump` CronJob)** — the **primary/only** backup (Decision 2). Local `pgdump-pvc`.
- **4.6 / 4.7 / 4.8 (MinIO + bucket/policy/user + ILM)** — Decision 3 (unchanged).
- **5.10 (Payload storage adapter)** — consumes the MinIO endpoint (`forcePathStyle`, scoped key) and the `payload` DB DSN.
- **14.6 (secrets pipeline)** — will materialise/rotate the Secrets these workloads consume; templates only for now.
- **14.8 (image pinning)** — governs the pinned tags/digests above.
- **4.9 (data-tier runbook)** — operational procedures (backup verify, restore drill, MinIO admin) go in the `## 3. Database & object storage ops (Epic 4 / 4.9)` section of `planning/deployment.md` (TBD placeholder today).
- **2.6 NetworkPolicy / 2.12 budget** — already in place; **no change** required by Epic 4 (keep Postgres on 5432, MinIO on 9000; set requests/limits within quota).

**Apply order (for the build epics; orchestrator wires `deploy/k8s/kustomization.yaml`, not this ADR):** Secrets (materialized out-of-band) + PVCs + initdb ConfigMap → Postgres `Deployment` → wait `pg_isready` → idempotent init Job (4.3) → MinIO `StatefulSet` → wait `/minio/health/ready` → MinIO setup Job (4.7, incl. 4.8 ILM).

---

## Forward-looking notes (not phase-1 actions)

- **Phase-2 multi-node / migration (Epic 1):** revisit Decision 1 — a multi-node cluster with an operator-comfortable posture *and* a durable archive target is where CloudNativePG (failover + PITR) finally pays off. `local-path`'s node-pinning and non-expandability are migration items to plan around (no online resize — re-size by restore into a larger PVC).
- **Phase-2 backup durability:** Decision 2's accepted same-box risk is retired by mapping `/backups` to an **off-box target (NAS, or a revived object-store copy)**. The CronJob shape stays; only the `pgdump-pvc` backing target changes. Reconsider off-site sooner if a compliance/retention requirement appears.
- **MinIO scale-out:** standalone → distributed (≥4 drives) is a phase-2 change if media volume or durability needs grow.

---

## Stale cross-references to clean up (out of 4.1 scope — flagged for follow-up)

This ADR is the authoritative record; the following pre-existing comments now contradict it and should be corrected by whoever owns those files (tracked here so a future reader does not mistake them for live intent):

- `deploy/k8s/namespaces.yaml` (2.5) — the `allpets-database` comment still lists "Postgres (CNPG)". Decision 1 chose a **plain Deployment, not CNPG**.
- `deploy/k8s/kustomization.yaml` — comment still references "DBs/CNPG"; same correction as above.
- `deploy/k8s/networkpolicies/backend-database.yaml` (2.6) — a comment notes Backblaze B2 egress is left open "so backups keep working". Decision 2 **dropped Backblaze**, so that note is stale (the policy itself needs no change for Epic 4).

---

## References

- Epic 04 spec (data tier): issues 4.1 (this ADR), 4.2, 4.3, 4.5, 4.6, 4.7, 4.8, 4.9; 4.4 / 1.9 (Backblaze — dropped).
- Owner decisions + cluster/spec reconciliation, **2026-06-15** (authoritative; overrides the epic-04 spec where they differ).
- aarogya Postgres on `quasar` — the familiar shape mirrored (and the three anti-patterns deliberately not copied).
- `deploy/k8s/resource-budget.yaml` (2.12 ResourceQuota/LimitRange for `allpets-database`).
- `deploy/k8s/networkpolicies/backend-database.yaml` (2.6 — already allows backend→database on 5432/9000).
- `deploy/k8s/namespaces.yaml` (2.5 — `allpets-database`).
- `planning/deployment.md` §3 (4.9 data-tier runbook — placeholder) and §1.7 (2.12 budget narrative).
- Related ADR: `planning/admin-surface-decision.md` (3.6 — MinIO console kept off public ingress aligns with that admin posture).
