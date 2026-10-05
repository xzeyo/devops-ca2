# devops-course-work

DevOps course work — weekly lab assignments + **CA-II** (case studies + tasks 1–5).

CA-II implements one small Java service end-to-end: containerized, deployed on
Kubernetes with zero-downtime rolling updates/rollback, configured with Ansible,
built through a GitHub Actions pipeline, and monitored with Prometheus + Grafana.
Everything was executed live on a single-node k3s cluster running on a Debian VM.

## Repository layout

```
.github/workflows/deploy.yml   CI/CD: build -> smoke test -> GHCR -> SSH deploy (Task 1)
app/src/Main.java              hello-service: zero-dependency Java HTTP service (Java 21)
Dockerfile                     canonical multi-stage build (JDK stage -> JRE-only runtime)
Dockerfile.local               runtime-only variant used on the VM (small /var partition)
k8s/deployment.yaml            3-replica Deployment, RollingUpdate maxSurge=1/maxUnavailable=0, probes
k8s/service.yaml               NodePort Service (8080 -> 30080)
k8s/servicemonitor.yaml        Prometheus ServiceMonitor (scrapes all pods every 5s)
k8s/grafana-dashboard.json     Grafana dashboard (uptime, rate, p95 latency, error rate)
ansible/playbook.yml           Task 2: packages + managed user + config files (idempotent)
ansible/inventory.ini          inventory (become password goes in ansible-vault, not here)
docs/                          pipeline + architecture + case-study diagrams, case-study deck
screenshots/                   task evidence: builds, deploys, rollout/rollback, dashboards, live captures
```

## Quick start (local Kubernetes)

```bash
docker build -t hello-service:1.0 .
kubectl apply -f k8s/deployment.yaml -f k8s/service.yaml
kubectl rollout status deployment/hello-service
curl http://localhost:30080/          # JSON: service, version, pod hostname, time

# rolling update
docker build -t hello-service:2.0 --build-arg APP_VERSION=2.0.0 .
kubectl set image deployment/hello-service hello-service=hello-service:2.0
kubectl rollout status deployment/hello-service

# rollback
kubectl rollout undo deployment/hello-service
```

Endpoints: `/` JSON info · `/health` probes · `/metrics` Prometheus format
(request counters by status code + latency histogram).

## Monitoring (Task 4)

kube-prometheus-stack via Helm; `k8s/servicemonitor.yaml` scrapes the service.
Grafana dashboard: import `k8s/grafana-dashboard.json` (datasource uid `prometheus`).

## CI/CD (Task 1)

`.github/workflows/deploy.yml` triggers on every push to `main`/`master`:
compile (javac 21) → docker build → container smoke test → push to GHCR →
deploy from the self-hosted runner on the VM (`k3s ctr images pull` → `kubectl set image` → `rollout status`
→ health check). Requires the repository secret `VM_PASS`; the deploy job runs on a self-hosted runner installed on the VM.
