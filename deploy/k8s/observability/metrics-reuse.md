# allpets metrics — reuse of the existing shared stack (issue 2.7)

**Decision (2026-06-14):** allpets reuses quasar's existing shared observability
stack (Grafana + Prometheus + kube-state-metrics + node-exporter + Alloy in the
`observability` namespace) instead of deploying a dedicated one — the box is a
tight 31 GiB shared node and the existing stack already covers the cluster.

## What allpets gets today (no infra change)

- **`kube-state-metrics`** is scraped by the shared Prometheus → allpets **object**
  metrics are already available cluster-wide and namespace-agnostic:
  `kube_pod_info`, `kube_pod_status_phase`, `kube_pod_container_status_restarts_total`,
  `kube_pod_container_resource_requests/limits`, `kube_resourcequota` (our 2.12
  quotas/usage), `kube_namespace_labels{namespace=~"allpets.*"}`.
- **`metrics-server`** (kube-system) → live usage via `kubectl top pods -n allpets-backend`
  (and `-n allpets-database` / `-frontend`). This is the immediate "is a pod hot?" view.
- **Logs**: auto-collected — see `logs-reuse.md` (2.11).

## The gap (and the fix, NOT auto-applied)

The shared Prometheus (`observability/prometheus-config`) uses **static targets**
and does **not** scrape **cAdvisor/kubelet**, so it has **no per-pod CPU/RAM _usage_
time series** (only kube-state object metrics). For historical/graphed allpets pod
usage in Grafana you must add cAdvisor + a pods autodiscovery job to that shared
config.

`prometheus-allpets-scrape.yaml` in this folder contains those scrape jobs,
ready to merge into `observability/prometheus-config`. **It is deliberately not
applied here** — that ConfigMap is shared infra serving aarogya/local-ai, so
applying it is gated on owner sign-off (it is purely additive and would also give
the *whole* cluster container metrics it currently lacks).

## Viewing allpets metrics in the shared Grafana

- Today: query `kube_*` metrics filtered to `namespace=~"allpets.*"`, or use
  `kubectl top`.
- After the scrape addition: import a standard Kubernetes dashboard
  (e.g. Grafana.com dashboards **315** "Kubernetes cluster monitoring (cAdvisor)"
  or **13332** "kube-state-metrics-v2") and scope its namespace variable to
  `allpets.*`. The existing Grafana has no dashboard sidecar, so dashboards are
  imported via the UI / API (admin creds in `observability/grafana-credentials`).

## App-level metrics

Each app's `/metrics` endpoint (Payload 5.x, Cal.com 6.x, Plausible 11.x) is added
as a static scrape target alongside `ai-proxy` in the shared `prometheus-config`
when that app deploys — owned by the app's epic, not 2.7.
