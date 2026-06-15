# allpets — k3s deployment runbook

> **File owner:** 17.4. Sections are contributed by their epics: **Cluster base
> setup** (Epic 2 / 2.9, below) · DNS/TLS/ingress (Epic 3 / 3.7) · Database &
> object storage ops (Epic 4 / 4.9) · CI/CD + rollback (Epic 15 / 15.8).
>
> **Rev-5 context:** Phase 1 runs on the **existing `quasar`** box, co-tenant with
> `local-ai-proxy`, `aarogya` (healthcare prod), home-assistant, etc. A dedicated
> clinic server is the deferred phase-2 migration (Epic 1, Backlog).

---

## 1. Cluster base setup (Epic 2)

### 1.1 Host (verified 2.1)
- `quasar`: **bare-metal Ubuntu 24.04.4** (kernel 6.8.0-124), **16 vCPU / 31 GiB RAM**,
  8 GiB swap, root disk **466 GB (LVM `ubuntu--vg/ubuntu--lv`, ~263 GB free)**.
- **No Proxmox, no ZFS** — storage is plain LVM/ext4. (The Epic-2 spec's ZFS-ARC /
  zvol concerns are **N/A** here; corrected from the Rev-5 "infra facts".)
- Access: SSH `sk@quasar`. `sk` has **no passwordless sudo** yet (15.12 sets that
  up); cluster ops use `sk`'s `~/.kube/config` (cluster-admin) — no sudo needed.

### 1.2 k3s + Traefik (verify-and-reuse, 2.3)
- **k3s v1.34.4+k3s1**, single node `quasar` (control-plane), containerd 2.1.5.
- Runs with **defaults**: flannel CNI, **embedded kube-router NetworkPolicy
  controller (enabled)**, ServiceLB (klipper), local-path-provisioner,
  metrics-server, CoreDNS — all healthy and reused.
- Ingress: **Traefik**, IngressClass `traefik`. Epic-3 Ingress objects set
  `ingressClassName: traefik`. Cluster API also on Tailscale
  (`quasar.tailb77b7f.ts.net`, `100.108.60.90`) for the Epic-15 deploy plane.

### 1.3 cert-manager + issuer (verify-and-reuse, 2.4)
- cert-manager + cainjector + webhook healthy.
- **ClusterIssuer `letsencrypt-prod`** Ready — ACME **production**
  (`acme-v02.api.letsencrypt.org`), email `skrx7392@gmail.com`, **DNS-01 via Route 53** (zone `kinvee.in`/`Z03680532RXMMGHR1HB0Y`; solver creds in the k8s secret `route53-credentials`, region `ap-south-1` — **not** HTTP-01).
  Proven by 6+ live certs. Epic-3 Ingress annotation:
  `cert-manager.io/cluster-issuer: letsencrypt-prod`.

### 1.4 Namespaces (2.5)
Apply: `kubectl apply -k deploy/k8s` (backend repo) **and**
`kubectl apply -k deploy/k8s` (frontend repo). Four labeled namespaces
(`app.kubernetes.io/part-of: allpets`):

| namespace | workloads |
|---|---|
| `allpets-frontend` | Next.js (defined in the **frontend** repo) |
| `allpets-backend` | Payload, Cal.com, Plausible app, GlitchTip(deferred) |
| `allpets-database` | Postgres (plain Deployment, **not** CNPG — 4.1), ClickHouse, MinIO |
| `allpets-observability` | reserved/empty (observability is reused — see 1.6) |

### 1.5 NetworkPolicies (2.6)
- Enforcement is **real** (k3s embedded netpol controller; verified: a frontend
  pod is refused to DB:5432, a backend pod succeeds, DNS works).
- Posture: **default-deny ingress** per namespace + explicit allows
  (Traefik→frontend:3000, frontend→backend, backend→database 5432/9000/8123,
  `observability`→scrape). **Egress left open** so SMTP/Google/B2/GHCR/DNS work.
- Label contract: app pods carry `app.kubernetes.io/part-of: allpets`; allows are
  namespace-scoped until app ports settle.

### 1.6 Observability — REUSE the shared stack (2.7/2.11; 2.8/2.10 deferred)
Decision: reuse quasar's existing `observability` stack (Grafana + Prometheus +
kube-state-metrics + node-exporter + **Alloy**) instead of a dedicated one.
- **Logs:** work with **zero change** — Alloy discovers all pods (no namespace
  filter) → allpets logs auto-ship to the shared Loki. Query
  `{namespace=~"allpets.*"}`. (`deploy/k8s/observability/logs-reuse.md`)
- **Metrics:** allpets covered by **kube-state-metrics** + **metrics-server**
  (`kubectl top -n allpets-*`). The shared Prometheus is `static_configs` + **no
  cAdvisor**, so per-pod *usage* series need a scrape addition
  (`deploy/k8s/observability/prometheus-allpets-scrape.yaml`, **owner sign-off** —
  shared infra). App `/metrics` added per app epic.
- **Deferred:** `2.8` Uptime Kuma (use a free external service pre-launch;
  cert-manager auto-renews TLS) · `2.10` GlitchTip (revisit when apps exist).
- **No-PII-in-logs** is the app epics' job (5.9/8.10/Cal.com).

### 1.7 Co-tenant resource budget (2.12) — the blast-radius guard
Every allpets namespace has a **LimitRange** (default req 100m/256Mi, limit
250m/512Mi + per-ns max) and a **ResourceQuota**:

| namespace | requests | limits |
|---|---|---|
| `allpets-database` | 2 cpu / 8Gi | 6 cpu / 12Gi |
| `allpets-backend` | 1.5 cpu / 4Gi | 5 cpu / 8Gi |
| `allpets-frontend` | 0.5 cpu / 1Gi | 2 cpu / 2Gi |
| `allpets-observability` | 0.5 cpu / 0.5Gi | 1 cpu / 1Gi |

Total allpets requests ~**4 vCPU / 13.5 GiB** of 16 / 31 — leaves wide headroom for
the co-tenants. Verified: a no-resources pod gets the LimitRange defaults + counts
against quota. **App epics set each pod's values within these caps.**
**Relief-lever order if RAM pressure hits:** (1) move Plausible to **Plausible
Cloud** (drops the heaviest in-cluster trio), (2) trim observability retention,
(3) the Epic-1 phase-2 hardware migration.

### 1.8 Reproducibility note
All allpets manifests apply via `kubectl apply -k deploy/k8s` (per repo). **Full
"rebuild cleanly on a fresh server from the repo" is deferred to the Epic-15
hardening pass** (capture substrate: `letsencrypt-prod` issuer + k3s install;
observability portability; a BOOTSTRAP/DR runbook with the secret checklist +
restore-from-B2 + Route 53 repoint). See `allpets-backend#131`.

---

## 2. DNS / TLS / ingress (Epic 3 / 3.7)

> **Owner:** 3.7 (this section). **Source of truth** for how an allpets public host
> gets DNS, a cert, and an Ingress on `quasar`. Built on the **2026-06-15 verified
> cluster + DNS facts**, which **override** the Epic-3 spec where they differ (the
> spec's HTTP-01 / port-80 assumption is stale — see §2.4). The canonical manifests
> and the recorded redirect + admin-surface decisions live in
> `deploy/k8s/ingress/README.md` (3.4 / 3.6), alongside
> `deploy/k8s/ingress/ingress-template.yaml` and
> `deploy/k8s/ingress/redirect-middleware.template.yaml`.

### 2.1 Architecture (request + cert paths)
- **Request path:** `Route 53 (kinvee.in)` → A record → **WAN `50.35.125.239`** →
  home/office router forwards **:80 / :443** → **Traefik** LoadBalancer svc
  (`kube-system/traefik`, LAN `10.0.10.113`, nodePorts 80:31544 / 443:32124) →
  per-host **Ingress** (`ingressClassName: traefik`) → the backend **Service** in
  its namespace.
- **Cert path (independent of the request path):** **cert-manager** sees the
  Ingress `cert-manager.io/cluster-issuer: letsencrypt-prod` annotation + `spec.tls`
  → solves **ACME DNS-01** by writing `_acme-challenge` **TXT** records into the
  `kinvee.in` Route 53 zone it already controls → Let's Encrypt issues → cert stored
  in the named `<host>-tls` Secret. **Port 80/443 reachability is not required for
  issuance** (works even behind CGNAT); it is only required to *serve* traffic.

### 2.2 DNS — Route 53 (`kinvee.in`)
- **Zone:** `kinvee.in`, **hosted-zone id `Z03680532RXMMGHR1HB0Y`**, PUBLIC,
  authoritative (registrar NS delegation matches the AWS set
  `ns-1385.awsdns-45.org` / `ns-1896.awsdns-45.co.uk` / `ns-467.awsdns-58.com` /
  `ns-597.awsdns-10.net`).
- **The three allpets hosts are live**, type **A** (not CNAME), TTL **300**, all →
  `50.35.125.239`, verified resolving from the authoritative NS and from `1.1.1.1` /
  `8.8.8.8`:

  | host | type | target | TTL |
  |---|---|---|---|
  | `allpets.kinvee.in` | A | `50.35.125.239` | 300 |
  | `book.allpets.kinvee.in` | A | `50.35.125.239` | 300 |
  | `analytics.allpets.kinvee.in` | A | `50.35.125.239` | 300 |

  Every co-tenant host (`ai`, `admin.ai`, `dev`, `api.dev`, `observe`, `y1`) is the
  same A → `50.35.125.239`, same box.
- **Write access:** AWS CLI as IAM user **`saikrishnareddy`** (account
  `591388266159`). **No AWS keys are committed to the repo** — they live in that
  operator's local `~/.aws` profile. (cert-manager has its *own* separate Route 53
  creds, in the k8s secret `route53-credentials`; see §2.4.)
- **Add / change an A record** (copy-paste; replace `<host>` only). `UPSERT` is
  idempotent — it creates or updates:
  ```bash
  HOST=newthing.allpets.kinvee.in       # the FQDN you want
  aws route53 change-resource-record-sets \
    --hosted-zone-id Z03680532RXMMGHR1HB0Y \
    --change-batch '{
      "Comment": "allpets: add '"$HOST"'",
      "Changes": [{
        "Action": "UPSERT",
        "ResourceRecordSet": {
          "Name": "'"$HOST"'",
          "Type": "A",
          "TTL": 300,
          "ResourceRecords": [{ "Value": "50.35.125.239" }]
        }
      }]
    }'
  # confirm it propagated from a public resolver:
  dig +short "$HOST" @1.1.1.1
  ```

### 2.3 Public IP / DDNS source
- WAN IP **`50.35.125.239`** is **effectively static** (home/office line; proven
  daily by `ai.kinvee.in`). The router statically forwards **80/443 → Traefik**.
- **If it ever goes dynamic:** the A records are the single point to update. Either
  (a) re-run the §2.2 `UPSERT` for each of the four+ allpets hosts with the new IP,
  or (b) stand up a DDNS updater (e.g. a small cron calling the same
  `change-resource-record-sets` with the detected IP, using a least-privilege Route
  53 IAM key). DNS-01 cert issuance is unaffected by an IP change — only request
  serving is. (Long-term, a phase-2 CGNAT line would move ingress to Tailscale
  Funnel / a tunnel — Epic 1; transport only, not this design.)

### 2.4 TLS — cert-manager, **DNS-01 via Route 53**
- **ClusterIssuer `letsencrypt-prod`**, ACME **production**
  (`https://acme-v02.api.letsencrypt.org/directory`), email `skrx7392@gmail.com`.
- **Solver is DNS-01 via Route 53** — `hostedZone Z03680532RXMMGHR1HB0Y`, region
  `ap-south-1`, creds in the **k8s secret `route53-credentials`** (referenced, **not
  inlined** — no access key in this doc or the repo). It is **not** HTTP-01.
- Consequence: because cert-manager already has write access to `kinvee.in`, the
  three allpets host certs issue with **zero extra issuer / RBAC / secret config** —
  just the Ingress annotation + `tls` block. There is **no
  `/.well-known/acme-challenge` HTTP path** anywhere, so an HTTP→HTTPS redirect is
  **safe** (it cannot break ACME).

### 2.5 Per-host Ingress (3.4) + namespaces + redirect decision
- **Pattern (mirror exactly):** `apiVersion: networking.k8s.io/v1`, `kind: Ingress`;
  annotation `cert-manager.io/cluster-issuer: letsencrypt-prod`;
  `spec.ingressClassName: traefik`; `spec.tls: [{ hosts: [<host>], secretName:
  <host>-tls }]`; one rule `host: <host>`, `path: /`, `pathType: Prefix` → the
  backend Service. This is the live `ai.kinvee.in` shape. Template:
  `deploy/k8s/ingress/ingress-template.yaml`.
- **An Ingress must live in the SAME namespace as its Service.** Host map:

  | host | namespace | backend (Service / port) | TLS secret | authored by |
  |---|---|---|---|---|
  | `allpets.kinvee.in` | `allpets-frontend` | Next.js site + Payload `/admin`, port 3000 | `allpets-kinvee-in-tls` | 7.8 (frontend repo) |
  | `book.allpets.kinvee.in` | `allpets-backend` | Cal.com, port 3000 | `book-allpets-kinvee-in-tls` | 6.3 |
  | `analytics.allpets.kinvee.in` | `allpets-backend` | Plausible, port 8000 | `analytics-allpets-kinvee-in-tls` | 11.1 |

  Backend ports are pinned to the committed NetworkPolicy contract
  (`networkpolicies/backend-database.yaml` allow-traefik-ingress: Cal.com 3000,
  Plausible 8000). **Change a port → update that NetworkPolicy in the same commit.**
- **HTTP→HTTPS redirect — DECISION: YES, a 308, per namespace.** Implemented as a
  Traefik `Middleware` (`redirectScheme: https`, `permanent: true`) referenced from
  each host's Ingress via `traefik.ingress.kubernetes.io/router.middlewares`.
  Template: `deploy/k8s/ingress/redirect-middleware.template.yaml`.
  - Safe because of DNS-01 (§2.4): no ACME HTTP path to clobber.
  - **Per namespace, not cluster-wide:** Traefik `allowCrossNamespace` is **off**, so
    a Middleware is only referenceable from its own namespace → one
    `redirect-https` Middleware in `allpets-backend` (covers `book` + `analytics`)
    and one in `allpets-frontend` (covers the site, authored in the frontend repo).
    **Apply the Middleware before the Ingress that references it.**
  - **Rejected alternative:** the cluster-wide Traefik `web→websecure` redirect arg —
    it would change behavior for every co-tenant including `aarogya` (healthcare
    prod). There is no such global arg today and we are not adding one.
  - This is a deliberate improvement over `ai.kinvee.in`, which still serves
    plaintext with no redirect.

### 2.6 Admin-surface protection (3.6) — **app-auth-only**
- Both sensitive surfaces — **Payload `/admin`** (on `allpets.kinvee.in`) and
  **Cal.com admin** (on `book.allpets.kinvee.in`) — are **app-auth-only**: a plain
  Ingress routes the whole host at `/`, with **no auth Middleware, no basic-auth, no
  forward-auth, no tailnet-only Ingress**. The application's own login is the only
  gate. Full rationale + rejected options are in the
  **DECISION — admin surface** section of `deploy/k8s/ingress/README.md`.
- Mirrors the in-prod precedent on this exact box (`admin.ai.kinvee.in`, plain
  Ingress, no middleware). Keeps Payload `/admin` reachable by non-technical clinic
  staff from any device. Brute-force / session defense is delegated to **5.11**
  (Payload auth: HttpOnly+Secure+SameSite cookies, no public signup) and **14.2**
  (rate-limiting) — **not** at ingress.
- **Do not** add a `/admin` path rule or any Traefik Middleware to these surfaces —
  the absence is a recorded decision, not an oversight.

### 2.7 Cert issuance, renewal & troubleshooting
- **Issuance is automatic** once the Ingress (annotation + `tls`) is applied:
  cert-manager creates a `Certificate` → `Order` → `Challenge` (DNS-01 TXT) →
  fetches the cert into `<host>-tls`. First issuance is typically < 2 min once DNS-01
  propagates.
- **Renewal is automatic:** cert-manager renews ~30 days before the 90-day expiry, no
  human action, no downtime. **Expiry alerting is out of scope here and handed to
  16.8** (monitor cert `notAfter` /
  `certmanager_certificate_expiration_timestamp`).
- **Staging-vs-prod gotcha:** the issuer is **`letsencrypt-prod`** (real, trusted
  certs). Do **not** point a host at a `letsencrypt-staging` issuer for "testing" —
  staging certs are signed by an untrusted root and browsers reject them. If a host
  ever shows an untrusted chain, first check the Ingress annotation names
  `letsencrypt-prod`.
- **Rate limits (Let's Encrypt production):** 50 certs / registered domain
  (`kinvee.in`) per week and **5 duplicate-cert (identical host set) issuances per
  week**. Avoid hammering re-issuance by repeatedly deleting/recreating a Secret. If
  you need to iterate, iterate against `letsencrypt-staging` first (separate, far
  higher limits) **then** flip to `letsencrypt-prod` for the real cert.
- **Inspect (in the host's namespace):**
  ```bash
  NS=allpets-backend            # or allpets-frontend
  kubectl get certificate -n "$NS"
  kubectl describe certificate <host>-tls -n "$NS"      # Events show why
  kubectl get order,challenge -n "$NS"                  # DNS-01 challenge state
  kubectl describe challenge -n "$NS" <name>            # 'presented'/'pending' TXT
  kubectl logs -n cert-manager deploy/cert-manager      # solver errors
  ```
  A challenge stuck `pending` usually means the `_acme-challenge` TXT has not
  propagated yet, or the `route53-credentials` secret lost Route 53 write access.
- **Verify the served cert end-to-end:**
  ```bash
  echo | openssl s_client -connect 50.35.125.239:443 -servername book.allpets.kinvee.in 2>/dev/null \
    | openssl x509 -noout -issuer -subject -dates
  # expect issuer = Let's Encrypt (R10/R11/E5…), subject CN/SAN = the host, valid dates
  ```

### 2.8 Add a new public host — operator checklist
Copy-paste, top to bottom; no prior context needed. Example host
`thing.allpets.kinvee.in`, Service `thing-svc:3000` in namespace `allpets-backend`.

1. **DNS** — create the A record (§2.2):
   ```bash
   HOST=thing.allpets.kinvee.in
   aws route53 change-resource-record-sets --hosted-zone-id Z03680532RXMMGHR1HB0Y \
     --change-batch '{"Changes":[{"Action":"UPSERT","ResourceRecordSet":{"Name":"'"$HOST"'","Type":"A","TTL":300,"ResourceRecords":[{"Value":"50.35.125.239"}]}}]}'
   dig +short "$HOST" @1.1.1.1     # → 50.35.125.239
   ```
2. **Redirect Middleware** — ensure a `redirect-https` Middleware exists **in the
   host's namespace** (`allpets-backend` already has one; a *new* namespace needs the
   `deploy/k8s/ingress/redirect-middleware.template.yaml` copy applied first).
3. **Ingress** — adapt the matching block from
   `deploy/k8s/ingress/ingress-template.yaml`: set host, `secretName: <host>-tls`
   (e.g. `thing-allpets-kinvee-in-tls`), backend Service name + port; keep
   `ingressClassName: traefik`, the `cert-manager.io/cluster-issuer:
   letsencrypt-prod` annotation, the `router.middlewares` redirect annotation, and a
   single `path: / pathType: Prefix` rule. The Ingress must be in the **same
   namespace** as the Service. Apply it.
4. **Watch the cert issue:**
   ```bash
   kubectl get certificate,order,challenge -n allpets-backend -w
   ```
5. **Verify it serves over HTTPS** (§2.7 `openssl s_client`) and that plain `http://`
   gets a 308 to `https://`:
   ```bash
   curl -sI http://thing.allpets.kinvee.in | grep -i '^location:'
   ```
6. If you changed the backend port from the NetworkPolicy contract, update
   `networkpolicies/backend-database.yaml` allow-traefik-ingress in the same commit.

### 2.9 Deferred: working host Ingresses (3.4) + cert verification (3.5)
The backend Services (5.13 Payload, 6.3 Cal.com, 11.1 Plausible, 7.8 site) **do not
exist yet**, and an Ingress must co-reside with its Service. So the **working host
Ingresses are intentionally NOT wired into any `kustomization.yaml`** today, and the
**3.5 cert verification is documented-but-deferred** to when the first workload
lands. 3.7/3.4 deliver the **canonical template + conventions**
(`deploy/k8s/ingress/`); each workload epic **folds in its own block** next to its
Service:
- **7.8 / 5.13** → `allpets.kinvee.in` Ingress in the **allpets-frontend repo**
  (frontend manifests live there), `secretName: allpets-kinvee-in-tls`.
- **6.3** → `book.allpets.kinvee.in` Ingress in `allpets-backend`,
  `secretName: book-allpets-kinvee-in-tls`.
- **11.1** → `analytics.allpets.kinvee.in` Ingress in `allpets-backend`,
  `secretName: analytics-allpets-kinvee-in-tls`.
When the first such Service is applied, run §2.8 steps 3–5 to bring its host fully
online and complete the deferred 3.5 verification.
## 3. Database & object storage ops (Epic 4 / 4.9)

> **Owner:** 4.9 (this section). **Source of truth** for operating allpets' data
> tier on `quasar`: **Postgres** (Payload + Cal.com), **MinIO** (Payload media), and
> the **nightly `pg_dump` backup**. Built on the **2026-06-15 owner decisions**,
> which **override** the Epic-4 spec where they differ: (a) Postgres is a **plain
> Deployment + PVC + Service**, *not* CloudNativePG (rationale + accepted trade-offs:
> `allpets-backend/planning/database-decision.md`, 4.1); (b) **no off-site backups** —
> Backblaze B2 (4.4 / 1.9) is **dropped**; the nightly `pg_dump` CronJob (4.5) to a
> **local PVC** is the **primary and only** backup. Manifests live under
> `deploy/k8s/database/`; the intra-namespace allows live under
> `deploy/k8s/networkpolicies/`. **No secrets are inlined here** — only Secret names.

### 3.1 Topology (what runs in `allpets-database`)
- **Postgres** — plain `Deployment` (1 replica, `strategy: Recreate` — RWO PVC,
  never two pods on one volume), pinned image `postgres:16.4` (digest preferred,
  14.8). Data on a **`local-path` RWO PVC** (`postgres-data`, ~10–20Gi) mounted at
  `/var/lib/postgresql/data` with `PGDATA=…/pgdata` (subdir avoids the `lost+found`
  init failure). The Postgres pod mounts **only** its data PVC + the initdb ConfigMap;
  the backup PVC (`pgdump-pvc`) is **not** mounted here — read dumps via a short-lived
  reader pod that mounts it (§3.6 / §3.8). `ClusterIP` Service **`postgres:5432`**.
  In-cluster DSN host: `postgres.allpets-database.svc.cluster.local:5432`.
- **MinIO** — standalone `StatefulSet` (1 replica; distributed needs ≥4 drives —
  phase 2), pinned `quay.io/minio/minio:RELEASE.2024-09-22T00-33-43Z`
  (**proposed pin — confirm/refresh the exact RELEASE date + digest on first
  deploy**, 14.8), `volumeClaimTemplate` 10Gi `local-path` at `/data`. `ClusterIP`
  Service **`minio`** with **9000** (S3 API) + **9001** (console, **not** publicly
  exposed — tailnet / `port-forward` only, per §2.6 admin posture). Payload endpoint
  (5.10): `http://minio.allpets-database.svc.cluster.local:9000`,
  `forcePathStyle: true`, bucket **`allpets-media`** (**private** — Payload
  serves/proxies media; no anonymous download).
- **pg_dump CronJob** — `0 3 * * *`, dumps **both** DBs in custom format to the
  `pgdump-pvc` (10Gi `local-path`, `/backups`), prunes archives >14 days.
- **Databases & roles (4.3):** two DBs `payload` (owner role `payload_app`) and
  `calcom` (owner role `calcom_app`). Each role has `LOGIN`, full DDL on **its own**
  DB only; `CONNECT` on the other DB is **revoked**, and `CONNECT … FROM PUBLIC` is
  revoked on both. Created on first boot by the initdb ConfigMap and (for an
  already-running cluster) by the idempotent **`postgres-init` Job**.
- All resource shapes set requests/limits within the **2.12** database quota
  (req 2 cpu / 8Gi, lim 6 cpu / 12Gi): Postgres req 500m/1Gi lim 2cpu/4Gi · MinIO
  req 250m/512Mi lim 1cpu/2Gi · `pg_dump` job req 100m/256Mi lim 500m/1Gi.

### 3.2 Secrets (names only — never inlined; created out-of-band)
The **14.6** GitHub-repo-secrets → k8s-Secret pipeline does **not exist yet**. The
repo carries **templates only** (`*.example.yaml`, placeholder `REPLACE_ME`); the
operator creates the **real** Secrets out-of-band with generated strong values
(`openssl rand`), keeping the **names and keys stable** so apps keep consuming them.
14.6 will own materialization/rotation later.

| Secret (ns `allpets-database`) | keys | consumed by |
|---|---|---|
| `postgres-secret` | `POSTGRES_USER` / `POSTGRES_PASSWORD` / `POSTGRES_DB` (bootstrap superuser+db); `PAYLOAD_APP_PASSWORD` / `CALCOM_APP_PASSWORD` (4.3 login roles) | Postgres Deployment (`envFrom`); `postgres-init` Job + initdb script (`env`); `pg_dump` CronJob (`secretKeyRef`) |
| `minio-root-secret` | `MINIO_ROOT_USER` / `MINIO_ROOT_PASSWORD` | MinIO StatefulSet (`envFrom`); MinIO setup Job |
| `minio-payload-key` | `MINIO_PAYLOAD_ACCESS_KEY` / `MINIO_PAYLOAD_SECRET_KEY` (scoped, least-priv) | MinIO setup Job provisions this user; Payload (5.10) uses it |

Templates: `deploy/k8s/database/postgres-secret.example.yaml`,
`deploy/k8s/database/minio-secret.example.yaml`. The `*.example.yaml` files are
**excluded** from kustomize `resources` (they carry `REPLACE_ME`).

### 3.3 Apply order (bring-up) — orchestrator wiring
The orchestrator wires the **durable** manifests into `deploy/k8s/kustomization.yaml`
(PVCs, ConfigMap, Services, StatefulSet, Deployment, CronJob, **and the two NEW
NetworkPolicies in §3.4**). The **`*.example.yaml` templates** and the **one-shot
Jobs** (`postgres-init`, `minio-setup`) are **not** in kustomize.

> **⚠️ NET-NEW NetworkPolicies — already wired in; apply order matters (read §3.4).**
> The 2.6 fact *"no new NetworkPolicy needed"* covered **only** the cross-namespace
> `allpets-backend → allpets-database` flow. It does **not** cover the **in-namespace**
> Job/CronJob → DB flows this epic introduces, which the committed
> `default-deny-ingress` (podSelector `{}`) **drops**. Two new files —
> **`deploy/k8s/networkpolicies/allow-intra-namespace-postgres.yaml`** and
> **`deploy/k8s/networkpolicies/allow-intra-namespace-minio.yaml`** — are **already
> listed in `deploy/k8s/kustomization.yaml` `resources:`** (next to
> `backend-database.yaml`) and apply with the rest of the tree, **before**
> Postgres/MinIO and the first Job/CronJob run. If they were ever removed,
> `postgres-init`, `minio-setup`, and every `pgdump-nightly` run **hang on connect and
> fail silently** (timeout, not error). This is the one place §3 knowingly deviates
> from an authoritative fact; the deviation is correct (see §3.4).

Order:

1. **Real Secrets** (out-of-band, from the templates) → `postgres-secret`,
   `minio-root-secret`, `minio-payload-key`.
2. **PVCs + initdb ConfigMap + Services + the two §3.4 NetworkPolicies.**
3. **Postgres Deployment** → wait ready
   (`kubectl -n allpets-database rollout status deploy/postgres --timeout=180s`).
4. **`postgres-init` Job** (idempotent roles/DBs) → wait
   `--for=condition=complete`. (On a fresh PVC the initdb ConfigMap already created
   everything on first boot; the Job is then a harmless no-op and the safety net for
   an already-initialized cluster. Re-running it also re-asserts the full 4.3
   REVOKE/GRANT posture in one shot — see §3.8.)
5. **MinIO StatefulSet** → wait ready
   (`kubectl -n allpets-database rollout status statefulset/minio`).
6. **`minio-setup` Job** (bucket + scoped policy + scoped user + `tmp/` ILM) → wait
   `--for=condition=complete`. The Job self-verifies the user/policy/attachment and
   exits non-zero on failure, so the wait is a trustworthy gate.
7. **`pgdump-pvc` + `pgdump-nightly` CronJob** — after Postgres is ready, the §3.4
   policies are in place, **and** the roles exist (the CronJob authenticates as
   `payload_app` / `calcom_app`). `pgdump-pvc` is consumed only by the CronJob's
   pods; read dumps for a restore via a short-lived reader pod (§3.6 / §3.8).

Job pod templates are immutable; to **re-run** any one-shot Job after an edit,
`kubectl -n allpets-database delete job <name>` then re-apply.

### 3.4 NetworkPolicy — the intra-namespace gap (NET-NEW; deviation from 2.6)
The committed `networkpolicies/backend-database.yaml` (2.6) puts
**`default-deny-ingress`** (`podSelector: {}`) on **every** `allpets-database` pod
and allows ingress **only** from the `observability` (scrape) and `allpets-backend`
(5432/9000/8123) namespaces. NetworkPolicy does **not** exempt same-namespace traffic
once a pod is selected, and k3s' embedded kube-router controller **enforces** this.
The **`postgres-init` Job**, the **`pg_dump` CronJob**, and the **`minio-setup` Job**
all run **inside** `allpets-database` and connect to `postgres:5432` / `minio:9000`
**in-namespace** — so without an explicit allow their connections are **dropped** and
they hang until timeout, then fail.

This is a **deviation from the 2.6 "no new NetworkPolicy needed" fact**, which was
true only for the cross-namespace backend→database flow; it did **not** anticipate
these in-namespace Job→DB flows. Two tightly-scoped, **NET-NEW** policies under
`deploy/k8s/networkpolicies/` close the gap (**durable — both are wired into
`deploy/k8s/kustomization.yaml`, see §3.3**):
- **`allow-intra-namespace-postgres.yaml`** — ingress to the Postgres pod
  (`podSelector` `app.kubernetes.io/name: postgres`) on **TCP 5432** from any pod in
  `allpets-database` (`namespaceSelector` `kubernetes.io/metadata.name:
  allpets-database`). Unblocks the init Job **and** the nightly backup.
- **`allow-intra-namespace-minio.yaml`** — ingress to the MinIO pod
  (`podSelector` `app.kubernetes.io/name: minio`) on **TCP 9000** from any pod in
  `allpets-database` (same `namespaceSelector`). Unblocks the `minio-setup` Job.

Both must be applied **before** Postgres/MinIO and the first Job/backup run.
The `verify-isolation` check (§3.5) uses `kubectl exec` over the **local socket**, so
it works regardless of NetworkPolicy.

### 3.5 Verify role isolation (4.3 acceptance) — run after EVERY restore
Connecting as `payload_app` to the `calcom` DB **must be denied**; writing to its own
DB must succeed. Runs over the in-pod local socket — independent of NetworkPolicy.
**Run this after every restore (§3.8), not only ownership-touching ones**, and after
the bring-up `postgres-init` Job:
```bash
# expect: FATAL: permission denied for database "calcom"
kubectl -n allpets-database exec deploy/postgres -- \
  sh -c 'PGPASSWORD="$PAYLOAD_APP_PASSWORD" psql -U payload_app -d calcom -c "select 1"'
# expect: success (owns its own DB)
kubectl -n allpets-database exec deploy/postgres -- \
  sh -c 'PGPASSWORD="$PAYLOAD_APP_PASSWORD" psql -U payload_app -d payload -c "create table _t(i int); drop table _t;"'
```
If isolation is *not* as expected, re-run the idempotent `postgres-init` Job to
re-assert the full 4.3 REVOKE/GRANT posture in one shot, rather than hand-running
individual statements:
```bash
kubectl -n allpets-database delete job postgres-init --ignore-not-found
kubectl -n allpets-database apply -f deploy/k8s/database/postgres-init-job.yaml
kubectl -n allpets-database wait --for=condition=complete job/postgres-init --timeout=120s
```

### 3.6 Backup — what the nightly job does
- **CronJob `pgdump-nightly`**, schedule **`0 3 * * *`** (`concurrencyPolicy:
  Forbid`, `restartPolicy: OnFailure`, history limits 7/7), image matched to the
  server (`postgres:16.4` client; client major ≥ server). For **each** of `payload`
  and `calcom`: `pg_dump --format=custom` → `/backups/<db>-<UTC-stamp>.dump` (atomic
  `.partial`-then-`mv`). **Rotation:** `find /backups -name '*.dump' -mtime +14
  -delete` in the same run (**14-day** retention). Authenticates per-DB as the app
  role using `PAYLOAD_APP_PASSWORD` / `CALCOM_APP_PASSWORD` from `postgres-secret`
  (no superuser, no plaintext). A root `fix-perms` initContainer (CHOWN-only) chowns
  `/backups` to the Postgres image's runtime UID/GID (**expected `999:999` for the
  Debian `postgres` image — confirm on first deploy**, §3.10) for the `local-path`
  (hostPath-backed) volume.
- **Confirm `0 3 * * *` is clear** of co-tenant nightly jobs (`aarogya`,
  `local-ai-proxy`) on quasar; if it contends, shift a slot (e.g. `0 4 * * *`) and
  record it here. Low risk given `Forbid` + the small ceiling (lim 500m/1Gi).
- **Smoke-test on first deploy** (also exercises the §3.4 policy). The dump files live
  on `pgdump-pvc`, which the Postgres pod does **not** mount — inspect via a tiny
  reader pod:
  ```bash
  kubectl -n allpets-database create job --from=cronjob/pgdump-nightly pgdump-manual-verify
  kubectl -n allpets-database wait --for=condition=complete job/pgdump-manual-verify --timeout=180s
  kubectl -n allpets-database logs job/pgdump-manual-verify   # log lists both dump files + sizes
  # confirm both files exist AND are owned by the dump UID (expected 999:999):
  kubectl -n allpets-database run pgdump-ls --rm -i --restart=Never --image=busybox \
    --overrides='{"spec":{"containers":[{"name":"r","image":"busybox","command":["ls","-ln","/backups"],"volumeMounts":[{"name":"b","mountPath":"/backups"}]}],"volumes":[{"name":"b","persistentVolumeClaim":{"claimName":"pgdump-pvc"}}]}}'
  kubectl -n allpets-database delete job pgdump-manual-verify
  ```

### 3.7 Backup-coverage matrix (HONEST — gaps are accepted, not hidden)
There is **no off-site copy of anything** in phase 1. `local-path` is node-pinned and
hostPath-backed: the database PVC and the backup PVC live on the **same disk in the
same box**, so a disk/host loss loses **both** the live data and its backups. This is
an **owner-accepted** phase-1 trade-off (4.1); the off-site/NAS story is **phase 2**.

| Source | What protects it | Off-site? | RPO | PITR? | Status |
|---|---|---|---|---|---|
| Postgres `payload` | nightly `pg_dump --format=custom` → `pgdump-pvc` (local), 14-day retention | **No** (same box) | **≤ 24h** (last 03:00 run) | **No** | **Backed up, accepted gap** (same-box) |
| Postgres `calcom` | nightly `pg_dump --format=custom` → `pgdump-pvc` (local), 14-day retention | **No** (same box) | **≤ 24h** | **No** | **Backed up, accepted gap** (same-box) |
| MinIO media (`allpets-media`) | **live `local-path` PVC only** — *no* dump/replication | **No** | **∞ / none** (loss = total media loss) | n/a | **GAP, accepted** — re-uploadable from source-of-truth content; not disaster-proof |
| Plausible ClickHouse | **out of Epic-4 scope** — owned by **11.6** | — | — | — | **Deferred to 11.6** |

- **RPO ≈ 24h** for both Postgres DBs (worst case: a loss at 02:59 forfeits ~a day
  of writes). **No PITR** (no WAL archiving / continuous backup in phase 1).
- **MinIO media is the weakest link**: only the live volume protects it. If media
  durability matters before phase 2, the lever is an `mc mirror` of `allpets-media`
  to a second target — **not** in scope here.

### 3.8 Restore a Postgres DB from a `pg_dump` archive
Custom-format dumps restore with `pg_restore`. **`--clean --create` must run as the
bootstrap superuser** (`POSTGRES_USER`): it issues `DROP DATABASE` / `CREATE DATABASE`,
which the app role (`payload_app` / `calcom_app`, `LOGIN`-only, no `CREATEDB`) **cannot**
do. Do **not** pass `--role=<db>_app` here — `--role` does `SET ROLE` *before*
`--create` runs the DDL, so the DROP/CREATE would execute as the unprivileged app role
and fail on a clean machine. Ownership is reassigned to the app role in a **separate**
step after the load. Copy-paste (example: restore `payload`):
```bash
NS=allpets-database
DB=payload                                  # or: calcom

# 1. Fetch the archive. The Postgres pod does NOT mount pgdump-pvc, so use a
#    short-lived reader pod to list dumps and copy the chosen one into the Postgres
#    pod's /tmp. Dump filenames are <db>-<UTC-stamp>.dump, e.g. payload-20260615T030000Z.dump:
kubectl -n "$NS" run pgdump-reader --restart=Never --image=postgres:16.4 \
  --overrides='{"spec":{"securityContext":{"fsGroup":999},"containers":[{"name":"r","image":"postgres:16.4","command":["sleep","1200"],"volumeMounts":[{"name":"b","mountPath":"/backups","readOnly":true}]}],"volumes":[{"name":"b","persistentVolumeClaim":{"claimName":"pgdump-pvc"}}]}}'
kubectl -n "$NS" wait --for=condition=ready pod/pgdump-reader --timeout=60s
kubectl -n "$NS" exec pgdump-reader -- sh -c 'ls -1t /backups/'"$DB"'-*.dump | head'
ARCHIVE=payload-20260615T030000Z.dump       # set to the chosen …Z.dump file
PGPOD=$(kubectl -n "$NS" get pod -l app.kubernetes.io/name=postgres -o jsonpath='{.items[0].metadata.name}')
kubectl -n "$NS" cp "pgdump-reader:/backups/$ARCHIVE" "/tmp/$ARCHIVE"
kubectl -n "$NS" cp "/tmp/$ARCHIVE" "$PGPOD:/tmp/restore.dump"
kubectl -n "$NS" delete pod pgdump-reader --wait=false
DUMP=/tmp/restore.dump

# 2. Stop the consuming app first (avoid writes during restore):
#    Payload runs in allpets-backend (5.x); Cal.com in allpets-backend (6.x).
kubectl -n allpets-backend scale deploy/payload --replicas=0      # adjust to the real Deployment name
#    (for calcom:  kubectl -n allpets-backend scale deploy/calcom --replicas=0)

# 3. Restore as the SUPERUSER with --clean --create (NO --role — DROP/CREATE need
#    superuser rights):
kubectl -n "$NS" exec -i deploy/postgres -- sh -c '
  PGPASSWORD="$POSTGRES_PASSWORD" pg_restore \
    --username="$POSTGRES_USER" --dbname=postgres \
    --clean --create --if-exists --no-owner \
    --exit-on-error '"$DUMP"'
'
#   --clean --create --if-exists : drop & recreate $DB, suppress "does not exist" noise
#   --no-owner                   : objects load owned by the connecting superuser;
#                                  ownership is reassigned to the app role in step 4

# 4. Reassign ownership to the app role and re-assert the 4.3 scoped posture
#    (idempotent; safe to re-run):
kubectl -n "$NS" exec deploy/postgres -- sh -c '
  PGPASSWORD="$POSTGRES_PASSWORD" psql -U "$POSTGRES_USER" -d postgres -v ON_ERROR_STOP=1 \
    -c "ALTER DATABASE '"$DB"' OWNER TO '"$DB"'_app;"
  PGPASSWORD="$POSTGRES_PASSWORD" psql -U "$POSTGRES_USER" -d '"$DB"' -v ON_ERROR_STOP=1 \
    -c "REASSIGN OWNED BY '"$DB"'_app TO '"$DB"'_app;" \
    -c "REVOKE CONNECT ON DATABASE '"$DB"' FROM PUBLIC;" \
    -c "GRANT ALL ON DATABASE '"$DB"' TO '"$DB"'_app;"
'
#    Simpler alternative to hand-running the above: re-run the idempotent
#    postgres-init Job (§3.5), which re-asserts the FULL 4.3 REVOKE/GRANT posture
#    for BOTH DBs (incl. the cross-DB CONNECT revokes a single --create can't touch).

# 5. Verify role isolation (§3.5 — REQUIRED after every restore), sanity-check, then
#    scale the app back up:
kubectl -n "$NS" exec deploy/postgres -- sh -c 'PGPASSWORD="$POSTGRES_PASSWORD" psql -U "$POSTGRES_USER" -d '"$DB"' -c "\dt" | head'
#    -> run the two §3.5 isolation commands here <-
kubectl -n allpets-backend scale deploy/payload --replicas=1     # restore the real replica count
```
> **Cross-DB isolation:** a `--create` of one DB does **not** touch the other DB's
> grants, so the §3.5 check normally still passes — but **run §3.5 after every
> restore** regardless. The one-shot `postgres-init` Job (§3.5) is the fastest way to
> re-assert the complete 4.3 posture (both DBs' `REVOKE CONNECT … FROM PUBLIC` and the
> cross-DB `REVOKE CONNECT` that scopes each role to its own DB) without
> hand-running individual `GRANT`/`REVOKE` statements.
>
> **Alternative path (no `--create`):** if you prefer not to drop the DB, pre-create
> it owned by `${DB}_app` and restore with `--no-owner --role=${DB}_app` into the
> existing DB (omit `--clean --create`). That keeps `SET ROLE` valid because no
> database-level DDL runs.

### 3.9 Credential rotation
All rotations change the **value** in the k8s Secret **and** the matching DB/MinIO
credential, then restart the consumers. Names/keys stay stable (§3.2). 14.6 will own
this once its pipeline exists; until then it is a manual, out-of-band operation.

**Postgres app roles (`payload_app` / `calcom_app`):**
```bash
NS=allpets-database
NEWPW=$(openssl rand -base64 24)
# 1. Change the role password in Postgres (example: payload_app):
kubectl -n "$NS" exec deploy/postgres -- sh -c '
  PGPASSWORD="$POSTGRES_PASSWORD" psql -U "$POSTGRES_USER" -d postgres -v ON_ERROR_STOP=1 \
    -c "ALTER ROLE payload_app WITH PASSWORD '"'"''"$NEWPW"''"'"';"'
# 2. Update the Secret key (PAYLOAD_APP_PASSWORD / CALCOM_APP_PASSWORD) — do this
#    out-of-band; never commit the value:
kubectl -n "$NS" patch secret postgres-secret --type merge \
  -p "{\"stringData\":{\"PAYLOAD_APP_PASSWORD\":\"$NEWPW\"}}"
# 3. Restart consumers so they pick up the new env (the app, and the backup picks it
#    up on its next run automatically):
kubectl -n allpets-backend rollout restart deploy/payload
```
> The **bootstrap superuser** (`POSTGRES_USER/PASSWORD`) is the same pattern but
> higher-risk: `ALTER ROLE`, patch `postgres-secret`, then
> `kubectl -n allpets-database rollout restart deploy/postgres`. Avoid unless needed.

**MinIO keys:**
- **Scoped Payload key** (`minio-payload-key`) — preferred rotation path. Mint a new
  scoped user via `mc admin user add` (root creds), attach the same scoped policy,
  patch `MINIO_PAYLOAD_ACCESS_KEY` / `MINIO_PAYLOAD_SECRET_KEY` in the Secret,
  `rollout restart` Payload, then `mc admin user remove` the old key. (Re-running the
  `minio-setup` Job after updating the Secret achieves the same provisioning step.)
- **MinIO root** (`minio-root-secret`) — rotate the root creds, patch the Secret,
  `kubectl -n allpets-database rollout restart statefulset/minio`. Higher blast
  radius; rotate the scoped key first.

**⚠️ DO NOT ROTATE — `CALENDSO_ENCRYPTION_KEY` (Cal.com):** this is **not** a
database/MinIO credential and is **not** in scope of the rotations above, but it is
the most dangerous "secret" near this tier. Cal.com uses
`CALENDSO_ENCRYPTION_KEY` to **encrypt data at rest in the `calcom` database**
(credentials, API keys, integration tokens). **Changing it makes all existing
encrypted rows undecryptable** — connected calendars/integrations break with no
recovery short of re-entering them. **Never** rotate it as part of a routine DB
password rotation, and **keep it identical** across any restore/re-deploy of Cal.com
(a `calcom` restore from §3.8 is only usable with the *same* key that was in effect
when the dump was taken). It is owned by the Cal.com app config (Epic 6), not Epic 4 —
flagged here so a "rotate all the DB secrets" sweep does not silently destroy Cal.com
data.

### 3.10 Image pinning & known follow-ups (proposals to confirm on first deploy)
- **Pin to digest before production** (14.8): manifests currently pin **tags**
  (`postgres:16.4`, `quay.io/minio/minio:RELEASE.2024-09-22T00-33-43Z`,
  `minio/mc:RELEASE.2024-09-16T17-43-14Z`). These exact tags are **proposals to
  confirm** when the `deploy/k8s/database/` manifests first land — pick the current
  stable RELEASE dates, then resolve and substitute digests:
  `docker buildx imagetools inspect postgres:16.4 --format '{{.Manifest.Digest}}'`.
  Keep tag+digest in sync across `postgres-deployment.yaml`,
  `postgres-init-job.yaml`, and `pgdump-cronjob.yaml`.
- **MinIO UID/GID smoke-test (verify, don't assume):** the StatefulSet is **proposed**
  to run MinIO as `runAsNonRoot` UID/GID 1000 with `fsGroup: 1000`, and the `pg_dump`
  `fix-perms` initContainer is proposed to chown `/backups` to `999:999` (the Debian
  `postgres` image's expected runtime UID/GID). **Both UIDs are image-default
  expectations to confirm on first deploy, not guarantees.** If MinIO CrashLoops on a
  fresh volume with `/data` / `.minio.sys` permission errors, drop
  `runAsUser`/`runAsGroup` (let the image's own user run) but **keep `fsGroup: 1000`**.
  If the backup files come up with an unexpected owner, adjust the `fix-perms` target
  to the UID shown by `kubectl exec deploy/postgres -- id`.
- **Single source of truth:** this §3 is the only operational runbook for the data
  tier. The 4.1 **decision/rationale** lives in `planning/database-decision.md`
  (links back here); the build manifests live in `deploy/k8s/database/`. Do not fork
  a competing runbook file.
## 4. CI/CD + rollback (Epic 15 / 15.8) — _TBD_
