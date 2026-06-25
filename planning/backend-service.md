# Backend service — developer & ops guide (20.6)

How to **build, run, test, migrate, and deploy** the allpets Spring Boot backend
(`allpets-api`). This is the operator quickstart; the **design** rationale lives in the
**[Backend LLD](lld-backend.md)** and the cluster/DNS/TLS substrate in the
**[deployment runbook](deployment.md)** + **[architecture HLD](architecture.md)**.

No secrets are inlined here — real credentials are created out-of-band (see
[Configuration & secrets](#configuration--secrets)).

---

## At a glance

| Task | Command |
|---|---|
| Compile + run tests | `./gradlew build` |
| Tests only (real Postgres, no Docker) | `./gradlew test` |
| Run locally on :8080 | `./gradlew bootRun` (needs a local Postgres — see below) |
| Health check | `curl localhost:8080/actuator/health` |
| Build the container image | `docker build -f deploy/Dockerfile.api -t allpets-api .` |
| Apply the cluster manifests | `kubectl apply -k deploy/k8s` |

**Stack:** Java **25** (toolchain-pinned), Gradle **9.5.1** (wrapper), Spring Boot
**3.5.15**, Spring Data JPA + **Flyway**, Postgres **16.4**. Package root
`com.allpets.api`, structured domain-first (`web · service · domain · repository`).

---

## Prerequisites

- **JDK 25** (Temurin recommended). The Gradle build pins the toolchain to 25, so the
  wrapper will use a JDK 25 if your default differs; install one if Gradle can't find it.
- **The Gradle wrapper** (`./gradlew`) — no system Gradle needed; it fetches 9.5.1.
- **Nothing else for build/test.** Tests start a **native embedded Postgres** (zonky
  `embedded-postgres` `2.1.0`, with the PG binary pinned to the prod minor **16.4** via
  the `embedded-postgres-binaries-bom`) — no Docker, no running database. The first test
  run downloads the PG binary (~tens of MB), then caches it.
- **For `bootRun` only:** a local Postgres (a throwaway Docker container is easiest — see below).

---

## Build, test, run

### Build & test

```bash
./gradlew build      # compile, then run the full test suite
./gradlew test       # tests only
```

Tests extend `support/PostgresIntegrationTest`, which boots one embedded Postgres per JVM
and pre-creates the `pgcrypto` + `citext` extensions (mirroring prod's DB init), so
**Flyway migrations and Hibernate `ddl-auto=validate` run against the same engine as
production**. A failing `validate` means a JPA entity drifted from the Flyway schema.

### Run locally (`bootRun`)

The app runs Flyway + JPA `validate` at startup, so it needs a Postgres with the `appdb`
database, the `app_svc` owner role, and the two extensions. Spin up a throwaway one:

```bash
docker run --rm --name allpets-pg -p 5432:5432 -e POSTGRES_PASSWORD=postgres postgres:16.4
# in another shell, create the role/db/extensions (extensions need a superuser):
docker exec -i allpets-pg psql -U postgres <<'SQL'
CREATE ROLE app_svc LOGIN PASSWORD 'devpw';
CREATE DATABASE appdb OWNER app_svc;
\connect appdb
CREATE EXTENSION IF NOT EXISTS pgcrypto;
CREATE EXTENSION IF NOT EXISTS citext;
SQL
```

Then point the app at it and run (Flyway creates the tables on first start):

```bash
SPRING_DATASOURCE_URL=jdbc:postgresql://localhost:5432/appdb \
SPRING_DATASOURCE_USERNAME=app_svc \
SPRING_DATASOURCE_PASSWORD=devpw \
./gradlew bootRun
```

Smoke-test it:

```bash
curl -s localhost:8080/actuator/health          # {"status":"UP",...}
curl -s -X POST localhost:8080/contact -H 'Content-Type: application/json' \
  -d '{"name":"Jamie","email":"jamie@example.com","message":"New patient?"}'
# -> 202 {"status":"received"}
```

(No Postgres install? The Postgres `Service`/`Deployment` also runs in the cluster; you
can `kubectl -n allpets-database port-forward svc/postgres 5432:5432` and point the env
vars at `localhost` instead.)

---

## Database & migrations (Flyway)

The schema is **owned by Flyway** — Hibernate never alters it (`ddl-auto=validate`).
Migrations live in `src/main/resources/db/migration/` as `V<n>__<description>.sql`,
applied in version order and **never edited after merge** (Flyway records a checksum).

To add a schema change:

1. Add `src/main/resources/db/migration/V2__<what_it_does>.sql` (forward-only).
2. Update/add the JPA entity to match (Hibernate `validate` fails the boot if the entity
   and the table disagree — that's the guard).
3. `./gradlew test` — the embedded-Postgres tests apply V1→V2 and validate the mapping.

**Extensions:** `V1__init.sql` uses `gen_random_uuid()` (pgcrypto) and `citext`, but the
`app_svc` role never runs `CREATE EXTENSION` — the extensions are created by a superuser at
DB init (the cluster's `postgres-initdb-configmap`; locally, the bootstrap SQL above; in
tests, `PostgresIntegrationTest`). Keep that contract: migrations assume the extensions exist.

> **Test caveat:** the embedded-Postgres tests run Flyway as a **superuser**, whereas
> production runs it as the non-superuser `app_svc`. A migration that needs elevated
> privileges (e.g. `CREATE EXTENSION`, or altering objects `app_svc` doesn't own) would
> **pass the tests but fail in prod** — keep every migration within `app_svc`'s grants (it
> owns `appdb`). For anything needing a superuser, add it to the DB-init step, not a migration.

---

## Configuration & secrets

All config is **environment-overridable** (`src/main/resources/application.yml`); **no
secrets in source or in this repo**.

| Env var | Purpose | Default |
|---|---|---|
| `SPRING_DATASOURCE_URL` | appdb JDBC URL | `jdbc:postgresql://localhost:5432/appdb` |
| `SPRING_DATASOURCE_USERNAME` | DB role | `app_svc` |
| `SPRING_DATASOURCE_PASSWORD` | DB password (**secret**) | *(empty)* |
| `ALLPETS_CORS_ALLOWED_ORIGINS` | CORS allowlist (comma-sep) | `https://allpets.skpodduturi.dev` |
| `JAVA_OPTS` | JVM flags (heap %, etc.) | `-XX:MaxRAMPercentage=75.0` (image) / `60.0` (Deployment) |
| `SERVER_PORT` | HTTP port | `8080` |
| `DB_POOL_MAX` | Hikari max pool | `5` |
| `GIT_SHA` / `BUILD_TIME` | `/actuator/info` build stamp | `dev` / `unknown` (CD injects real values) |

**Secrets are created out-of-band** (never committed; the real filenames are gitignored).
Templates show the shape:

- **`deploy/k8s/api/allpets-api-secret.example.yaml`** → the app's runtime Secret
  `allpets-api-secret` in `allpets-backend`. Phase 1 consumes `SPRING_DATASOURCE_PASSWORD`
  (= the `app_svc` role password, i.e. the same value as `APP_SVC_PASSWORD` in the
  database's `postgres-secret` — a workload can't read a Secret across namespaces). Mail
  (Epic 13) and the Google Places key (Epic 10) are pre-shaped for when those land.
- **`deploy/k8s/cert-manager/cloudflare-api-token-secret.example.yaml`** → `cloudflare-api-token-secret`
  in the `cert-manager` namespace (the DNS-01 solver token).
- **`deploy/k8s/database/postgres-secret.example.yaml`** → the DB's `postgres-secret`.

Create them with `kubectl create secret generic …` (preferred — no file on disk) or via a
`/tmp` file you `shred` after applying. Never write the real Secret into the repo tree.

---

## Container image

`deploy/Dockerfile.api` is a multi-stage build (Gradle build → `eclipse-temurin:25-jre-alpine`
runtime), runs **non-root**, exposes **8080**, and sets `-XX:MaxRAMPercentage` so the JVM
heap honours the container memory limit.

```bash
docker build -f deploy/Dockerfile.api -t allpets-api .   # context = repo root
```

In the pipeline, **CI (Epic 15.4)** builds and pushes this image to **GHCR**
(`ghcr.io/gimballock-1/allpets-api`), and **CD** (push-based over Tailscale, mirroring
local-ai-proxy) pins an immutable `@sha256` digest per deploy and rolls it onto `quasar`.
`GIT_SHA` / `BUILD_TIME` are injected at deploy time. (The committed `deployment.yaml` uses
a `:main` placeholder tag until CI lands — not a pin.)

---

## Deploy to the cluster

All backend/shared manifests apply as one kustomize tree:

```bash
kubectl apply -k deploy/k8s
```

What's in the tree (relevant to this service):

- **`deploy/k8s/api/`** — the `allpets-api` `Deployment` + `Service` (:8080) + the
  `api.allpets.skpodduturi.dev` `Ingress`.
- **`deploy/k8s/cert-manager/letsencrypt-cloudflare.yaml`** — the dedicated Cloudflare
  DNS-01 `ClusterIssuer`.
- **`deploy/k8s/ingress/redirect-https.yaml`** — the `allpets-backend` 308 HTTP→HTTPS Middleware.
- **`deploy/k8s/networkpolicies/backend-database.yaml`** — `allow-traefik-ingress`
  (Traefik→:8080/3000/8000) and `allow-from-backend` (backend→Postgres 5432 / MinIO 9000 /
  ClickHouse 8123). There is deliberately **no** `allow-from-frontend` (the site reaches the
  API over the public host, not in-cluster).
- **`deploy/k8s/database/`** — Postgres + MinIO + the nightly `pg_dump` CronJob.

### Live-cutover checklist (first bring-up)

`kubectl apply -k` is necessary but **not sufficient** — and on a fresh cluster the
namespaced Secrets must exist **before** the workloads start, so order matters.
(Assumes cert-manager + Traefik are already installed on the cluster.)

1. **Namespaces first** — `apply -k` creates the allpets namespaces only at step 6, but the
   app/DB Secrets below live in them, so create them up front:
   ```bash
   kubectl apply -f deploy/k8s/namespaces.yaml
   ```
2. **DNS** — in Cloudflare, create the `skpodduturi.dev` **A-records** (`allpets`, `api.allpets`,
   `book.allpets`, `analytics.allpets`) → `50.35.125.239`, **DNS-only / gray-cloud** (proxy OFF).
3. **cert-manager token** — create the real `cloudflare-api-token-secret` in the `cert-manager`
   namespace (least-privilege `Zone.DNS:Edit` + `Zone.Zone:Read` on `skpodduturi.dev`):
   ```bash
   kubectl -n cert-manager create secret generic cloudflare-api-token-secret --from-literal=api-token='<TOKEN>'
   ```
4. **Secrets** — `apply -k` includes the database substrate, so **all three** must exist
   first or the Postgres / MinIO / API pods fail to start on a fresh cluster:
   - **`allpets-api-secret`** (`allpets-backend`) — the app's `app_svc` DB password:
     ```bash
     kubectl -n allpets-backend create secret generic allpets-api-secret \
       --from-literal=SPRING_DATASOURCE_PASSWORD='<app_svc password>'
     ```
   - **`postgres-secret`** (`allpets-database`) — superuser + app-role passwords; create it
     from `deploy/k8s/database/postgres-secret.example.yaml`. Its `APP_SVC_PASSWORD` **must
     equal** the `SPRING_DATASOURCE_PASSWORD` above.
   - **`minio-root-secret`** (`allpets-database`) — MinIO root creds; create it per
     `deploy/k8s/database/minio-secret.example.yaml`. (`minio-payload-key` is only needed by
     the MinIO setup Job, which the root `apply -k` does not run.)
5. **Image** — there is no CI yet (Epic 15.4 owns the GHCR build/push), and `deployment.yaml`
   references `ghcr.io/gimballock-1/allpets-api:main`, so until CI lands, build and push it by
   hand, then add the pull Secret **if the GHCR package is private**:
   ```bash
   docker build -f deploy/Dockerfile.api -t ghcr.io/gimballock-1/allpets-api:main .
   echo "$GHCR_PAT" | docker login ghcr.io -u <github-user> --password-stdin
   docker push ghcr.io/gimballock-1/allpets-api:main
   kubectl -n allpets-backend create secret docker-registry ghcr-pull \
     --docker-server=ghcr.io --docker-username=<github-user> --docker-password="$GHCR_PAT"
   ```
   (Skip the `ghcr-pull` Secret if the package is public. **Never commit the PAT.**)
6. **Apply** — `kubectl apply -k deploy/k8s`.
7. **One-time cleanup** — drop the obsolete policy if an earlier apply left it behind:
   ```bash
   kubectl -n allpets-backend delete netpol allow-from-frontend --ignore-not-found
   ```
8. **Verify** — see below.

### Verify

```bash
kubectl -n allpets-backend get deploy,pod -l app.kubernetes.io/name=allpets-api
kubectl -n allpets-backend get certificate api-allpets-skpodduturi-dev-tls   # READY=True
curl https://api.allpets.skpodduturi.dev/actuator/health                     # UP, valid cert (no -k)
# CORS: allowed origin passes preflight (echoes the origin + Content-Type), others rejected
curl -si -X OPTIONS https://api.allpets.skpodduturi.dev/contact \
  -H 'Origin: https://allpets.skpodduturi.dev' \
  -H 'Access-Control-Request-Method: POST' \
  -H 'Access-Control-Request-Headers: Content-Type' \
  | grep -i access-control-allow
```

---

## The public host: DNS · TLS · CORS

- **Host:** `api.allpets.skpodduturi.dev` — its own public origin (not a path under the
  site), served by Traefik. The site's server-side `/api` proxy calls it cross-origin; the
  browser stays same-origin.
- **TLS:** cert-manager **DNS-01 via Cloudflare** using the dedicated `letsencrypt-cloudflare`
  `ClusterIssuer` (the shared `letsencrypt-prod`/Route53 issuer is left untouched). No port-80
  needed; the 308 redirect can't break ACME.
- **CORS:** enforced **in Spring** (`config.CorsConfig`), not at the ingress — allowlist
  `https://allpets.skpodduturi.dev`, credentials off, methods `GET`/`POST`, header `Content-Type`.
  Override per-env with `ALLPETS_CORS_ALLOWED_ORIGINS`.

---

## Troubleshooting

| Symptom | Likely cause | Fix |
|---|---|---|
| Boot fails: `Schema validation: missing table/column` | JPA entity drifted from the Flyway schema | Add a `V<n>__…` migration to match the entity (don't edit a merged migration) |
| Boot fails: Flyway `checksum mismatch` | An already-applied migration file was edited | Revert the edit; make changes in a new `V<n>` |
| Boot fails: `function gen_random_uuid() does not exist` / `type "citext"` | Extensions not pre-created in that DB | `CREATE EXTENSION pgcrypto; CREATE EXTENSION citext;` as a superuser |
| Pod **Running** but never **Ready** | readiness gates on `db`; appdb unreachable / bad password | Check `allpets-api-secret`, the DSN, and that Postgres is up + reachable (netpol `allow-from-backend`) |
| `ImagePullBackOff` | image not pushed yet, or private package without a pull secret | Confirm the manual push (or CI, once 15.4 lands) pushed the tag; create the `ghcr-pull` Secret if the package is private |
| `OOMKilled` at startup/under load | heap + non-heap exceeds the memory limit | The Deployment caps heap at 60% of the 1.5Gi limit (`JAVA_OPTS`); raise the limit or lower the % |
| Browser CORS error from the site | origin not in the allowlist | Set `ALLPETS_CORS_ALLOWED_ORIGINS` to include the calling origin |
| `certificate … not Ready` | Cloudflare token missing/invalid, or the A-record/zone not set | Check the `cloudflare-api-token-secret` Secret + `kubectl -n allpets-backend describe certificate …`/`order`/`challenge` |

---

## File map

| Path | What |
|---|---|
| `src/main/java/com/allpets/api/` | the service (domain-first packages) |
| `src/main/resources/application.yml` | env-overridable config |
| `src/main/resources/db/migration/` | Flyway migrations |
| `src/test/java/com/allpets/api/` | tests (real Postgres via `support/PostgresIntegrationTest`) |
| `deploy/Dockerfile.api` | container image |
| `deploy/k8s/` | the kustomize apply tree (api workload, issuer, ingress, netpols, database) |

See the **[Backend LLD](lld-backend.md)** for the design behind all of this.
