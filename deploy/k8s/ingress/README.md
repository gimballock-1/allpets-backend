# allpets ingress — canonical per-host Traefik Ingress pattern (issue 3.4)

This directory is the **single source of truth for how an allpets public host is
exposed** over HTTPS on quasar. It owns the *pattern*; the per-workload epics
(5.13, 6.3, 7.8, 11.1) fold it into their own manifests. It mirrors the proven,
in-production `local-ai/ai-proxy-ingress` (for `ai.kinvee.in`) on this exact box,
with one deliberate improvement (a firm HTTP→HTTPS redirect — see below).

> **Status — working host Ingresses are BLOCKED, by design.** The backend
> Services these Ingresses route to (5.13 Payload, 6.3 Cal.com, 11.1 Plausible,
> 7.8 Next.js site) **do not exist yet**. An Ingress whose backend Service is
> missing gives Traefik a dangling route, so we do **not** apply these now.
> 3.4 therefore delivers the **canonical template + conventions** (this README +
> `ingress-template.yaml` + `redirect-middleware.template.yaml`); the working
> Ingress for each host is applied by its owning epic when the Service lands, and
> **3.5 (cert verification) is documented-but-deferred** to that point. Nothing
> here is wired into `../kustomization.yaml` yet for the same reason.

---

## Cluster facts this pattern is built on

The **Traefik / cluster** facts below were verified against the live cluster on
**2026-06-15** and **override the Epic-3 spec**, which predates the verification
and wrongly assumes HTTP-01. The **DNS / TLS** facts (Cloudflare zone, the
dedicated `letsencrypt-cloudflare` issuer, the `cloudflare-api-token` secret) are
**post-pivot design to be stood up** — the `skpodduturi.dev` Cloudflare zone is
net-new to cert-manager and was **not** part of the 2026-06-15 verification.

- **Traefik**: single-node k3s, `kube-system/traefik` LoadBalancer, WAN
  `50.35.125.239` (router forwards 80/443). Providers `kubernetesingress` +
  `kubernetescrd`. Entrypoints `web` (:80) and `websecure` (:443, TLS on).
  **No** global web→websecure redirect arg. **`allowCrossNamespace` is OFF** —
  a Middleware can only be referenced from its own namespace.
- **No Traefik Middleware objects and no IngressRoute CRDs exist** anywhere.
  Every co-tenant uses plain `networking.k8s.io/v1` Ingress.
- **cert-manager**: ACME **production** via **DNS-01**, solver provider
  **Cloudflare** (zone `skpodduturi.dev`). Author a **NEW dedicated Cloudflare
  DNS-01 ClusterIssuer** for allpets (e.g. `letsencrypt-cloudflare`) rather than
  editing the shared cluster-wide `letsencrypt-prod` issuer (which also serves
  the aarogya healthcare-prod tenant and `ai.kinvee.in`). The Cloudflare zone is
  **net-new to cert-manager** — there is no pre-existing write access to inherit —
  so issuance needs the new solver **plus** a k8s secret `cloudflare-api-token`
  (a least-privilege API token: `Zone.DNS:Edit` + `Zone.Zone:Read` on
  `skpodduturi.dev`). This is the **inverse** of the old Route53 setup, where
  cert-manager already owned the zone and certs issued with zero extra config.
  **DNS-01 means port-80 reachability is NOT required for issuance, and there is
  no ACME HTTP path to protect.**
- **DNS**: `allpets`, `book.allpets`, `analytics.allpets`.skpodduturi.dev are all
  live **A** records → `50.35.125.239`, managed in the **Cloudflare dashboard**
  (or via the Cloudflare API). Set them **DNS-only / "gray-cloud"** (proxy OFF) so
  Traefik + cert-manager + the 308 redirect behave exactly as before — Cloudflare
  is the DNS provider only, **not** a reverse proxy or Tunnel. Confirm each record
  resolves publicly once created (the zone is net-new).

---

## Host → namespace → backend → TLS-secret map

| Host | Namespace | Backend Service (intended) | Port | TLS secret | Owning epic(s) | Manifest lives in |
|---|---|---|---|---|---|---|
| `allpets.skpodduturi.dev` | `allpets-frontend` | Next.js site (Payload `/admin` same host) | 3000 | `allpets-skpodduturi-dev-tls` | 7.8 + 5.13 | **allpets-frontend repo** |
| `book.allpets.skpodduturi.dev` | `allpets-backend` | Cal.com self-hosted | 3000 | `book-allpets-skpodduturi-dev-tls` | 6.3 | this repo |
| `analytics.allpets.skpodduturi.dev` | `allpets-backend` | Plausible CE | 8000 | `analytics-allpets-skpodduturi-dev-tls` | 11.1 | this repo |

Rules that are **non-negotiable** for every allpets host Ingress:

1. `spec.ingressClassName: traefik`.
2. annotation `cert-manager.io/cluster-issuer: letsencrypt-cloudflare` (the new
   dedicated allpets Cloudflare DNS-01 issuer — **not** the shared `letsencrypt-prod`).
3. a `spec.tls` block whose `secretName` is exactly `<host>-tls` (dots → dashes,
   per the table). cert-manager writes the cert into that secret **in the
   Ingress's own namespace**.
4. The Ingress lives in the **same namespace as its backend Service** (a k8s
   Ingress can only target a Service in its own namespace). This is why there is
   one Ingress per host, not one shared object — the backends span two
   namespaces.
5. Backend ports match the already-committed NetworkPolicy contract
   (`../networkpolicies/backend-database.yaml` → `allow-traefik-ingress`:
   Cal.com 3000, Plausible 8000). If a workload epic picks a different port, it
   **must** update that NetworkPolicy too, or Traefik→pod traffic is dropped.

Canonical shape (copy from `ingress-template.yaml`):

```yaml
apiVersion: networking.k8s.io/v1
kind: Ingress
metadata:
  name: <workload>-ingress
  namespace: <backend-namespace>
  labels:
    app.kubernetes.io/part-of: allpets
    allpets.skpodduturi.dev/tier: <frontend|backend>
  annotations:
    cert-manager.io/cluster-issuer: letsencrypt-cloudflare
    traefik.ingress.kubernetes.io/router.middlewares: <namespace>-redirect-https@kubernetescrd
spec:
  ingressClassName: traefik
  tls:
    - hosts: [<host>]
      secretName: <host>-tls
  rules:
    - host: <host>
      http:
        paths:
          - path: /
            pathType: Prefix
            backend:
              service:
                name: <service>
                port:
                  number: <port>
```

---

## DECISION — HTTP→HTTPS redirect: **YES, add a firm 308 redirect (per-namespace)**

**Recommendation (firm):** every allpets host redirects `http://` → `https://`
with a `308 Permanent Redirect`, via a Traefik `redirectScheme` Middleware
referenced from the Ingress annotation.

**Why this is the right call (and a deliberate improvement over the
`ai.kinvee.in` reference, which serves plaintext with no redirect):**

- allpets is a **public marketing + booking + admin** surface. The site sets
  Payload/Cal.com **session cookies**; serving any of it over plaintext risks
  cookie/credential leakage and downgrade. A redirect closes that.
- The usual reason teams *avoid* an HTTP→HTTPS redirect on a fresh host is fear
  of breaking the **ACME HTTP-01 challenge** (`/.well-known/acme-challenge/…`).
  **That fear does not apply here.** This cluster issues certs by **DNS-01**
  (Cloudflare TXT records). There is **no HTTP ACME path** to intercept, so the
  redirect **cannot break cert issuance** — it works even before any cert exists,
  and even behind CGNAT. This removes the only real argument against redirecting.
- It is also forward-compatible with HSTS (14.1), which assumes HTTPS-only.

**Why per-namespace (the load-bearing implementation detail):** the cluster has
**no** global web→websecure redirect arg and **no** Middleware objects, and
Traefik's **`allowCrossNamespace` is OFF**. A Middleware can only be referenced
by an Ingress in its **own** namespace. So the `redirect-https` Middleware must
be **instantiated in each allpets namespace** that has a redirecting Ingress:

- `allpets-backend` — one Middleware serves both `book` and `analytics`.
  Shipped in this repo: `redirect-middleware.template.yaml` (fold into kustomize
  with 6.3/11.1, **ahead of** the Ingress that references it).
- `allpets-frontend` — needs its own copy (different repo, different namespace).
  Reproduced as a comment in `redirect-middleware.template.yaml`; the frontend
  repo carries the real object.

The annotation form is exact: `traefik.ingress.kubernetes.io/router.middlewares:
<namespace>-<middlewarename>@kubernetescrd` (e.g.
`allpets-backend-redirect-https@kubernetescrd`). The `<namespace>-` prefix and
the `@kubernetescrd` provider suffix are both required by Traefik.

> **Alternative considered and rejected:** a single cluster-wide
> `--entrypoints.web.http.redirections.entrypoint.to=websecure` Traefik arg
> would redirect *every* tenant (aarogya, local-ai, home-assistant, …) at once.
> That is a shared-infra behavior change affecting healthcare prod and is out of
> scope for an allpets epic. Per-namespace Middleware keeps the blast radius to
> allpets only. **Do not add the global arg.**

---

## DECISION — admin surface (folds in 3.6): **APP-AUTH-ONLY, no extra middleware**

Both sensitive surfaces — Payload `/admin` (on `allpets.skpodduturi.dev`) and the
Cal.com admin (on `book.allpets.skpodduturi.dev`) — are protected by **application
login only**. **No basic-auth, no forward-auth, no tailnet-only Ingress, no
`/admin` path middleware** is added at the ingress layer.

Rationale:
- This is the **proven in-prod pattern on this exact box**: the live
  `local-ai-admin/admin-frontend-ingress` for `admin.ai.kinvee.in` is a plain
  Ingress at `/` with no auth middleware — app-auth-only.
- **Non-technical clinic staff must reach Payload `/admin` from arbitrary
  devices.** A tailnet-only admin Ingress would require every staff device to
  join the tailnet, which is unacceptable for this surface (the 3.6 acceptance
  criterion explicitly calls this out). Basic-auth adds a clunky second
  credential wall and another secret to manage; forward-auth needs an auth
  provider we don't run.
- Brute-force exposure of the public login page is mitigated at the **app layer**
  by 5.11 (Payload auth: HttpOnly+Secure+SameSite cookies, no public signup) and
  14.2 (rate-limiting) — not at ingress.

**Concrete instruction to implementers:** route the **whole host** at `path: /`.
Do **not** add a second `/admin` path rule or any middleware beyond
`redirect-https`. Payload serves `/admin` itself on the same origin behind the
Next.js site.

---

## How each epic folds this template in

General rule (from the reproducibility decision): **all new manifests go under a
`deploy/k8s` kustomize tree; the epic that owns the namespace owns the Ingress
file placement.** 3.4 owns the shared pattern only — it does not pre-create
applyable Ingress objects (that would double-own manifests and dangle on missing
Services).

### 5.13 + 7.8 — `allpets.skpodduturi.dev` (site + Payload `/admin`), ns `allpets-frontend`
- The Ingress and its `redirect-https` Middleware are authored **in the
  allpets-frontend repo** (that repo owns the `allpets-frontend` namespace).
- Copy the `allpets.skpodduturi.dev` block from `ingress-template.yaml` and the
  commented `allpets-frontend` Middleware from `redirect-middleware.template.yaml`
  into `allpets-frontend/deploy/k8s/ingress.yaml` (or wherever 7.8 places site
  manifests). Add both to the frontend kustomization, Middleware first.
- Reconcile the Service `name`/`port` to the actual Next.js Service from 7.8.
- Keep a single `/` rule (app-auth-only). Payload (5.13) rides the same host.

### 6.3 — `book.allpets.skpodduturi.dev` (Cal.com), ns `allpets-backend`
- Copy the `book.allpets.skpodduturi.dev` block from `ingress-template.yaml` into this
  repo next to the Cal.com Deployment/Service (e.g.
  `deploy/k8s/calcom/ingress.yaml`).
- Ensure the `allpets-backend` `redirect-https` Middleware
  (`redirect-middleware.template.yaml`) is in the kustomize tree **before** this
  Ingress.
- Cal.com keeps its **own host** (cookies / OAuth callbacks, req §9) — never a
  path under `allpets.skpodduturi.dev`. Reconcile Service `name`/`port` (port 3000 is
  the NetworkPolicy contract).

### 11.1 — `analytics.allpets.skpodduturi.dev` (Plausible), ns `allpets-backend`
- Copy the `analytics.allpets.skpodduturi.dev` block from `ingress-template.yaml` into
  this repo next to the Plausible Deployment/Service (e.g.
  `deploy/k8s/plausible/ingress.yaml`).
- Reuse the **same** `allpets-backend` `redirect-https` Middleware that `book`
  uses (one Middleware per namespace serves both). Reconcile Service `name`/`port`
  (port 8000 is the NetworkPolicy contract).

When the first allpets-backend Service lands, add its Ingress + the shared
Middleware to `deploy/k8s/kustomization.yaml` (Middleware before Ingress).

---

## Verification (this is 3.5 — deferred until the first Service exists)

Once a workload's Service is live and its Ingress is applied, verify per host:

1. `kubectl -n <ns> get ingress` — host + backend correct.
2. `kubectl -n <ns> get certificate <host>-tls -w` until `READY=True`. With
   DNS-01 there is **no** HTTP challenge; if stuck, check
   `kubectl -n <ns> get order,challenge` and that the Cloudflare TXT record was
   written (and that the `cloudflare-api-token` secret is valid). (No port-80
   troubleshooting applies here.)
3. Confirm prod issuer (not staging):
   `kubectl get clusterissuer letsencrypt-cloudflare -o jsonpath='{.spec.acme.server}'`
   → `https://acme-v02.api.letsencrypt.org/directory`.
4. `echo | openssl s_client -servername <host> -connect <host>:443 2>/dev/null |
   openssl x509 -noout -issuer -subject -dates` → Let's Encrypt issuer, correct
   CN, ~90-day `notAfter`.
5. `curl -I http://<host>/` → `308` to `https://` (redirect working).
6. `curl -I https://<host>/` (no `-k`) → response from the intended backend, no
   cert warning.

Mind Let's Encrypt rate limits (50 certs/registered-domain/week, 5 duplicate/
week) — don't churn Ingress objects during debugging.

---

## Files in this directory

| File | Purpose |
|---|---|
| `README.md` | this — the canonical pattern, host map, redirect + admin decisions, fold-in instructions |
| `ingress-template.yaml` | one ready-to-adapt Ingress block per host (allpets / book / analytics), each in its correct namespace |
| `redirect-middleware.template.yaml` | the per-namespace `redirect-https` Traefik Middleware + the annotation snippet |
