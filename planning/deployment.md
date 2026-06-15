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
| `allpets-database` | Postgres/CNPG, ClickHouse, MinIO |
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
## 3. Database & object storage ops (Epic 4 / 4.9) — _TBD_
## 4. CI/CD + rollback (Epic 15 / 15.8) — _TBD_
