# allpets-backend

Backend + shared infrastructure for the **All Pets Veterinary Hospital** website &
self-serve scheduler (phase 1). Deploys to the existing `quasar` k3s cluster.

## Design docs

- **[System architecture (HLD)](planning/architecture.md)** — components, two-database boundary, namespace topology, DNS/TLS, deploy-vs-ingress planes, secrets, CI/CD, decisions log.
- **[Backend LLD](planning/lld-backend.md)** — the **Spring Boot** service (Java 25, Gradle, Spring Data JPA + Flyway) on `api.allpets.kinvee.in`: contact-form + reviews-cache + email + actuator, `appdb`↔Postgres↔MinIO wiring, CORS allowlist, Cal.com & Plausible integration, per-app k8s wiring, secrets contract.
- **[Frontend LLD](https://github.com/gimballock-1/allpets-frontend/blob/main/planning/lld-frontend.md)** — the Next.js marketing site (lives in the `allpets-frontend` repo).
- **[Deployment runbook](planning/deployment.md)** — k3s base setup, DNS/TLS/ingress, database & object-storage ops.
- ADRs: **[database decision](planning/database-decision.md)** (plain Postgres, no off-site backup) · **[admin-surface decision](planning/admin-surface-decision.md)** (app-auth-only).

## Backend service (Spring Boot)

The custom API is a Spring Boot service (Java 25, Gradle Kotlin DSL, Spring Boot 3.x)
in `com.allpets.api`, structured domain-first (see `src/main/java/com/allpets/api/package-info.java`).

```bash
./gradlew build        # compile + test
./gradlew bootRun      # run locally on :8080
curl localhost:8080/actuator/health   # {"status":"UP",...}
```

- Health/metrics: `/actuator/health` (with `liveness` + `readiness` groups), `/actuator/info`
  (build `GIT_SHA`/`BUILD_TIME`), `/actuator/prometheus`.
- Config is env-overridable (`SERVER_PORT`, `GIT_SHA`, `BUILD_TIME`, …) — no secrets in source.
- Container image: `deploy/Dockerfile.api` (multi-stage, non-root, `-XX:MaxRAMPercentage`).
- Persistence: Postgres `appdb` (owner role `app_svc`), schema owned by **Flyway**
  (`src/main/resources/db/migration/`), Hibernate `ddl-auto=validate`. Tests run against a
  real Postgres (no Docker) via embedded-postgres; CI adds a migration-drift guard (15.4).

> The `POST /contact` endpoint lands in 20.3, the api host + ingress in 20.4, and the
> full developer/ops quickstart in 20.6.

## Deploy

Cluster manifests live under `deploy/k8s/` and apply as one tree:

```bash
kubectl apply -k deploy/k8s
```

> A full developer quickstart is tracked in issue 17.8.
