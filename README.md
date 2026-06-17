# allpets-backend

Backend + shared infrastructure for the **All Pets Veterinary Hospital** website &
self-serve scheduler (phase 1). Deploys to the existing `quasar` k3s cluster.

## Design docs

- **[System architecture (HLD)](planning/architecture.md)** — components, two-database boundary, namespace topology, DNS/TLS, deploy-vs-ingress planes, secrets, CI/CD, decisions log.
- **[Backend LLD](planning/lld-backend.md)** — Payload data model, Payload↔Postgres↔MinIO wiring, Cal.com & Plausible integration, per-app k8s wiring, secrets contract.
- **[Frontend LLD](https://github.com/gimballock-1/allpets-frontend/blob/main/planning/lld-frontend.md)** — the Next.js marketing site (lives in the `allpets-frontend` repo).
- **[Deployment runbook](planning/deployment.md)** — k3s base setup, DNS/TLS/ingress, database & object-storage ops.
- ADRs: **[database decision](planning/database-decision.md)** (plain Postgres, no off-site backup) · **[admin-surface decision](planning/admin-surface-decision.md)** (app-auth-only).

## Deploy

Cluster manifests live under `deploy/k8s/` and apply as one tree:

```bash
kubectl apply -k deploy/k8s
```

> A full developer quickstart is tracked in issue 17.8.
