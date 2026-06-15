# allpets logs — reuse of the existing shared Loki (issue 2.11)

**Reuse works with zero infra change.** quasar's existing **Grafana Alloy**
DaemonSet (`observability` namespace) is configured with:

```alloy
discovery.kubernetes "pods" { role = "pod" }   // NO namespace filter — ALL pods
loki.source.kubernetes "pods" { targets = discovery.relabel.pods.output
                                forward_to = [loki.write.default.receiver] }
loki.write "default" { endpoint { url = "http://loki:3100/loki/api/v1/push" } }
```

Because Alloy discovers **all** pods cluster-wide (no namespace scoping) and tags
each log stream with `namespace`, `pod`, `container`, `app`, **allpets pod logs are
collected into the shared Loki automatically the moment allpets app pods run** —
nothing to deploy or configure for allpets. This satisfies req §8.6 (logs survive
pod restart/reschedule; aggregated + searchable).

## Querying allpets logs (existing Grafana → Loki datasource)

```logql
{namespace="allpets-frontend"}                      # Next.js
{namespace="allpets-backend"}                       # Payload / Cal.com / Plausible
{namespace="allpets-database"}                      # Postgres / MinIO / ClickHouse
{namespace=~"allpets.*"} |= "error"                 # errors across allpets
{namespace="allpets-backend", app="payload"}        # one app (needs the `app` label)
```

## Retention

Loki retention is owned by the **shared** stack config (not allpets). If allpets
log volume becomes material, coordinate a retention/compactor cap with the
observability stack owner (this is the shared-box version of the 2.12 "cap the
sneakiest unbounded grower" lever). Tracked for the deployment.md runbook (2.9).

## ⚠️ No-PII-in-logs (req §8.4) — pushed to the app epics

Aggregation cannot scrub what apps emit. The apps that handle PII MUST NOT log it:
- **Payload `ContactSubmission` (5.9)** and the **Contact form (8.10)** — never log
  request bodies / contact-form / intake fields.
- **Cal.com** — keep booker PII out of app logs.
Loki just stores stdout; these issues own keeping PII out of it.
