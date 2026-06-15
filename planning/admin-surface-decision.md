# ADR 3.6 — Admin-surface protection (app-auth vs Traefik forward-auth/basic-auth vs tailnet-only)

> **Type:** Spike / recorded decision (Epic 3 · 3.6). This file is the *output* — a decision, not infrastructure. The chosen mechanism is implemented by **3.4** (the per-host Traefik Ingress objects). It is not a build task here.
>
> **Status:** Accepted — 2026-06-15.
>
> **Scope:** how the two sensitive admin surfaces are protected at the ingress layer:
> - **Payload `/admin`** — served on `allpets.kinvee.in` (same host as the marketing site), namespace `allpets-frontend`.
> - **Cal.com admin** — served on `book.allpets.kinvee.in`, namespace `allpets-backend`.
>
> `analytics.allpets.kinvee.in` (Plausible) is out of scope for this ADR except where noted; Plausible's own login gates its dashboard and it is not a clinic-staff content surface.

---

## Decision (summary)

| Surface | Host | Namespace | **Decision** | TLS secret | Instruction to 3.4 |
|---|---|---|---|---|---|
| Payload `/admin` | `allpets.kinvee.in` | `allpets-frontend` | **App-auth-only** (plain Ingress, no middleware) | `allpets-kinvee-in-tls` | No extra ingress middleware on `/admin`; single `/` rule to the Next.js/Payload Service. |
| Cal.com admin | `book.allpets.kinvee.in` | `allpets-backend` | **App-auth-only** (plain Ingress, no middleware) | `book-allpets-kinvee-in-tls` | No extra ingress middleware; dedicated-host `/` rule to the Cal.com Service. |

**Both surfaces: app-auth-only.** No Traefik `Middleware` (no forward-auth, no basic-auth), no second tailnet-scoped Ingress. The application's own login is the authentication boundary; ingress only terminates TLS and routes the Host header. Brute-force exposure of the public login pages is mitigated at the *application* layer by rate-limiting (Epic 14.2), not at the ingress.

This matches the **in-prod precedent on this exact box** and the breakdown's lean note, and is the lowest-risk choice given the verified cluster state below.

---

## Context (verified cluster facts that frame this decision)

The Epic-3 spec text predates a 2026-06-15 cluster verification; where they differ, the verified facts below win. The relevant ones for *this* decision:

1. **In-prod precedent — admin surface is already app-auth-only on quasar.** The live `local-ai-admin/admin-frontend-ingress` for `admin.ai.kinvee.in` is a plain `networking.k8s.io/v1` Ingress: `ingressClassName: traefik`, cert-manager annotation, `spec.tls`, a single `/` rule — **no auth middleware, no basic-auth, no forward-auth**. The proven, in-production pattern for an admin surface on this node is app-auth-only. We are mirroring a setup that already works, not inventing one.

2. **No Traefik Middleware machinery exists anywhere in the cluster.** There are zero `Middleware` objects and zero `IngressRoute` CRDs in the cluster today; every co-tenant (aarogya, local-ai, home-assistant, grafana) uses a plain `networking.k8s.io/v1` Ingress. Choosing forward-auth or basic-auth would mean introducing the *first* Middleware on the box. Additionally, Traefik's `allowCrossNamespace` is **not** set, so a `Middleware` can only be referenced from its own namespace — any admin-path middleware would have to be authored and maintained inside `allpets-frontend` / `allpets-backend`, as net-new surface with its own failure modes. App-auth-only adds none of this.

3. **Certs issue via DNS-01, not HTTP-01.** The `letsencrypt-prod` ClusterIssuer uses the **DNS-01** Route53 solver (cert-manager already controls the `kinvee.in` zone). There is therefore **no `/.well-known/acme-challenge` HTTP path** that an admin protection could accidentally clobber, and an HTTP→HTTPS redirect cannot break cert issuance. This removes the only reason an ingress-layer auth control would need careful path-exclusion work — but it does not change the recommendation, which stays app-auth-only on its own merits.

4. **Same physical box, residential/office WAN, effectively static IP, port-forwarded 80/443.** No special perimeter exists beyond the router forward; the security boundary that matters for these surfaces is the application login, which is exactly what app-auth-only relies on.

---

## Options considered (per the spike's framing)

### (a) App-auth-only — rely on Payload / Cal.com login
The admin UI is reached over public HTTPS; the application's own authenticated session is the only gate. Zero extra ingress infra.

- **Pros:** zero additional infra; identical to the proven `admin.ai.kinvee.in` precedent on this box; no second credential store/secret to rotate; works from *any* device for non-technical clinic staff (just a browser + their login); no coupling to the tailnet or to a forward-auth provider we don't otherwise run; nothing for the ingress layer to get wrong (no path-exclusion, no middleware namespace gotcha).
- **Cons:** the login page is internet-facing, i.e. a brute-force / credential-stuffing surface. **Mitigation:** application-level rate-limiting + lockout (Epic 14.2), strong session cookie hygiene (HttpOnly + Secure + SameSite, no public signup — req §5.3, owned by 5.11 for Payload / 6.x for Cal.com). It does not hide the *existence* of the admin endpoint.

### (b) Traefik forward-auth or basic-auth middleware in front of the admin path
A `Middleware` on the `/admin` path (basic-auth credential wall) or a forward-auth handoff to an external identity provider.

- **Pros:** adds a credential wall *before* the app, shrinking the public attack surface of the app login itself; for forward-auth, centralizes auth/SSO if such a provider existed.
- **Cons:** **forward-auth needs an auth provider we do not run** — net-new infrastructure for a solo-operator phase-1 build, contradicting the deploy decision's minimalism. **Basic-auth** is a second secret to manage and rotate, gives a clunky browser-native prompt that confuses non-technical staff (two logins: the basic-auth box, then the Payload login), and would be the **first `Middleware` object in the cluster** (point 2 above) — added operational surface and a per-namespace maintenance burden because `allowCrossNamespace` is off. For Cal.com specifically, a basic-auth wall in front of the whole host would also sit in front of *non-admin* booking traffic unless carefully path-scoped, which Cal.com's routing makes brittle. Not justified by the phase-1 threat model.

### (c) Tailnet-only admin Ingress — expose `/admin` only on the tailnet
Expose the admin surface only on the Tailscale tailnet (the deploy plane, 15.11/15.12), not on the public internet.

- **Pros:** strongest network-level isolation — the admin endpoint is simply not reachable from the public internet; eliminates the public brute-force surface entirely. Appropriate for **operator-only** surfaces.
- **Cons (decisive for Payload):** the surface becomes reachable **only from a tailnet-joined device**. **Non-technical clinic staff must be able to edit site content (hours, services, team, announcements) from arbitrary devices** — a front-desk PC, a personal phone, a home laptop — without installing and logging into Tailscale. Tailnet-only is therefore **unsuitable for Payload `/admin`** unless that hard constraint is explicitly accepted, and it is **not** accepted: routine content editing by non-tailnet staff is an in-scope phase-1 workflow. Tailnet-only also entangles the **public-ingress plane with the deploy plane**, which Rev 3 deliberately keeps orthogonal (see "Relationship to 15.11/15.12").

---

## Per-surface decisions and rationale

### Payload `/admin` (`allpets.kinvee.in`, ns `allpets-frontend`) → **app-auth-only**

**Rationale.** Payload `/admin` is a **clinic-staff content surface**: non-technical staff must reach it from arbitrary, un-managed devices to update hours, services, team bios, and announcements. That single requirement eliminates option (c) tailnet-only — we are not requiring staff to join the tailnet, and that constraint is *explicitly rejected*, not silently assumed. Between (a) and (b), the in-prod precedent on this exact box (`admin.ai.kinvee.in`) is already app-auth-only with a plain Ingress and no middleware, and Payload's own auth (5.11; HttpOnly+Secure+SameSite cookies, no public signup, per req §5.3) is a *required* deliverable regardless of this decision — so adding a Traefik basic-auth/forward-auth wall would be redundant infrastructure (a second login UX hostile to non-technical staff, a second secret to rotate, and the first `Middleware` in a cluster where `allowCrossNamespace` is off). The residual risk — a public, brute-forceable login page — is real but is the *correct layer* to defend at the application: rate-limiting and lockout (14.2). **Decision: app-auth-only; no `/admin` middleware; single `/` Ingress rule routing the host to the Next.js/Payload Service.**

**Trade-off accepted:** the Payload login page is internet-facing. Mitigated by 14.2 (rate-limit/lockout) + 5.11 (session cookie hygiene, no public signup). Not mitigated: endpoint existence is discoverable. Judged acceptable for the phase-1 bar.

### Cal.com admin (`book.allpets.kinvee.in`, ns `allpets-backend`) → **app-auth-only**

**Rationale.** `book.allpets.kinvee.in` must serve **public booking traffic** on the same host; the Cal.com admin is a path/area within that same application, not a separable host. A network-level control (tailnet-only) is impossible without breaking public booking, and a host-wide basic-auth/forward-auth wall would sit in front of the *public booking flow* too — unacceptable. Path-scoping a middleware to just the Cal.com admin routes is brittle against Cal.com's internal routing and, again, would be the first `Middleware` in the cluster for marginal benefit. Cal.com ships its own authenticated admin/login with no-public-signup configuration (6.x), which is the appropriate boundary. **Decision: app-auth-only; no middleware; dedicated-host `/` Ingress rule to the Cal.com Service** (keep `book` a dedicated host — never a path under `allpets` — because Cal.com needs its own host for cookies/OAuth callbacks, req §9).

**Trade-off accepted:** same as Payload — public login surface, mitigated at the app layer (Cal.com login throttling + 14.2 where it applies). Booking traffic is public by design, so no network gate is even desirable here.

### Why not reserve tailnet-only for *something*?

Tailnet-only remains the right tool for **truly operator-only surfaces** (e.g. internal ops dashboards, debug endpoints, the deploy plane itself) — surfaces only the solo operator (a tailnet member) ever touches. Neither admin surface in scope qualifies, because both are reached by people who are not tailnet members (clinic staff for Payload; the public for Cal.com booking on the same host). If a future operator-only endpoint appears, it should default to tailnet-only — but that is a separate, later decision, not this one.

---

## Instruction handed to 3.4 (concrete, per surface)

3.4 implements exactly this; no admin-protection guesswork remains.

- **Payload `/admin` (`allpets.kinvee.in`):** **No extra ingress middleware (app-auth-only).** Author the single `allpets-frontend`-namespaced Ingress per the canonical reference shape — `apiVersion: networking.k8s.io/v1`, `kind: Ingress`, `metadata.annotations: { cert-manager.io/cluster-issuer: letsencrypt-prod }`, `spec.ingressClassName: traefik`, `spec.tls: [{ hosts: [allpets.kinvee.in], secretName: allpets-kinvee-in-tls }]`, one rule `host: allpets.kinvee.in`, `http.paths: [{ path: /, pathType: Prefix, backend → Next.js/Payload Service }]`. Do **not** add a separate `/admin` rule, a `traefik.ingress.kubernetes.io/router.middlewares` annotation, basic-auth, or forward-auth. (Ingress is authored in the **allpets-frontend repo** by 7.8, which copies this shared pattern; 3.4 owns the pattern.)
- **Cal.com admin (`book.allpets.kinvee.in`):** **No extra ingress middleware (app-auth-only).** Author the `allpets-backend`-namespaced Ingress with the identical shape — `secretName: book-allpets-kinvee-in-tls`, one rule `host: book.allpets.kinvee.in`, `path: /` Prefix → Cal.com Service. Dedicated host only; never a path under `allpets`. No middleware/auth annotation.
- **HTTP→HTTPS redirect:** owned by **3.4**, which decides **YES — a 308 redirect via a per-namespace Traefik `redirect-https` Middleware** (safe because DNS-01 means there is no ACME HTTP path to protect). This is orthogonal to this admin decision: the admin surfaces simply ride the same `redirect-https` Middleware their host already carries — they get no *additional* middleware beyond it.
- **Do not introduce any Traefik `Middleware` object** for these surfaces. None exists in the cluster and `allowCrossNamespace` is off; adding one is net-new surface this decision rejects.

Note in the manifests/runbook that the absence of middleware is a **deliberate, recorded decision (this ADR)**, not an oversight — so a later reviewer doesn't "helpfully" add a basic-auth wall.

---

## Relationship to other issues (so they don't conflict)

- **5.11 (Payload app auth) — the authentication boundary this ADR relies on.** App-auth-only *delegates* admin protection entirely to 5.11. 5.11 must deliver the controls that make a public login acceptable: HttpOnly + Secure + SameSite session cookies, no public/self-signup, sane password policy, and (with 14.2) login rate-limiting/lockout (req §5.3). If 5.11 ever weakened to a publicly-registerable admin, this ADR would need revisiting. No conflict today: 5.11 already owns these and this ADR adds no contradicting ingress gate.
- **14.2 (app-level rate-limiting) — the brute-force mitigation.** The single accepted trade-off (internet-facing login pages) is mitigated here, at the app layer, for both Payload and Cal.com login. This ADR explicitly hands brute-force defense to 14.2 rather than to ingress.
- **6.x (Cal.com auth/config) — Cal.com's own gate.** Mirrors 5.11 for the Cal.com surface: authenticated admin + no public signup. Same delegation, same dependency.
- **15.11 / 15.12 (Tailscale deploy plane) — kept orthogonal.** The tailnet is the **deploy plane** (CI/CD push over Tailscale, Rev 3); this ADR deliberately does **not** route any public admin surface through it. By choosing app-auth-only we keep the public-ingress plane and the deploy plane separate, exactly as Rev 3 intends — no admin Ingress depends on tailnet membership, so 15.11/15.12 can evolve the tailnet freely without touching public admin access. Tailnet-only is reserved for future operator-only surfaces, which is consistent with (not competing for) the deploy plane's purpose.

---

## Forward-looking note (not a phase-1 action)

If phase-2 migration puts quasar behind clinic **CGNAT** (no port-forward possible), public ingress would move to Tailscale Funnel / a tunnel / a cloud reverse-proxy (tracked under Epic 1). That changes the *transport*, not this decision: the admin surfaces stay app-auth-only at the application layer regardless of how public traffic reaches Traefik. Re-evaluate only if the threat model changes (e.g. a compliance requirement for network-level admin isolation), not merely because the network path changes.

---

## References

- Epic 3 spec: `planning/issues/epic-03-dns-tls-ingress.md` §3.6, §3.4 (admin-surface reflection in the Ingress), §3.7 (runbook records this decision).
- In-prod precedent: live `local-ai-admin/admin-frontend-ingress` (`admin.ai.kinvee.in`) and `local-ai/ai-proxy-ingress` (`ai.kinvee.in`) — plain Ingress, no middleware.
- Verified cluster + DNS facts, 2026-06-15 (DNS-01 Route53 solver; no Middleware/IngressRoute in cluster; `allowCrossNamespace` unset; single-node k3s Traefik).
- req §5.3 (auth/session cookies, no public signup), §8.4 (TLS via cert-manager + Let's Encrypt), §9 (domains/hosts; Cal.com dedicated host).
- Rev 3 (deploy plane = Tailscale, orthogonal) + Rev 4 (no Cloudflare Access) changelogs; breakdown 3.6 lean note.

