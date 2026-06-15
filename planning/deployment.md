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
  (`acme-v02.api.letsencrypt.org`), email `skrx7392@gmail.com`, HTTP-01 via Traefik.
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

## 2. DNS / TLS / ingress (Epic 3 / 3.7) — _TBD_
## 3. Database & object storage ops (Epic 4 / 4.9) — _TBD_
## 4. CI/CD + rollback (Epic 15 / 15.8) — _TBD_
