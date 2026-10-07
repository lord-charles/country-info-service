# Deploying to Kubernetes

## 1. What gets deployed

```
k8s/
├── base/                      # environment-agnostic application manifests
│   ├── namespace.yaml         # country-info namespace, "restricted" Pod Security Standard
│   ├── serviceaccount.yaml    # no API token mounted
│   ├── configmap.yaml         # non-secret config (DB URL, SOAP endpoint, log format, sampling)
│   ├── deployment.yaml        # 3 replicas, probes, resources, security context, graceful shutdown
│   ├── service.yaml           # ClusterIP :80 -> pod :8080
│   ├── hpa.yaml               # 3..10 replicas on CPU 70% / memory 80%
│   ├── pdb.yaml               # at most 1 pod disrupted during drains/upgrades
│   ├── networkpolicy.yaml     # least-privilege ingress/egress
│   └── ingress.yaml           # NGINX: /api, /swagger-ui, /v3/api-docs; rate limit + timeouts
├── mysql/                     # in-cluster MySQL StatefulSet + headless Service (dev/demo only)
└── overlays/
    ├── local/                 # kind/minikube: base + mysql, 2 replicas, dev secret
    └── production/            # managed DB, registry image, TLS, bigger resources
```

| Concern | How it is handled |
|---|---|
| Containerisation | Multi-stage `Dockerfile`, layered jar, JRE-only runtime, non-root UID 10001 |
| Configuration | ConfigMap (env vars) + Secret; Kustomize overlays per environment; no rebuild to change config |
| Health checks | `startupProbe` (up to 150s for JVM start), `livenessProbe` `/actuator/health/liveness`, `readinessProbe` `/actuator/health/readiness` (includes DB) |
| Scalability | HPA, stateless pods, topology spread, PDB, rolling update with `maxUnavailable: 0` |
| Graceful shutdown | `preStop` sleep 10s (endpoint removal propagates), Spring `server.shutdown=graceful` (20s drain), `terminationGracePeriodSeconds: 45` |
| Security | `runAsNonRoot`, read-only root FS, all capabilities dropped, seccomp `RuntimeDefault`, NetworkPolicy |

## 2. Prerequisites

| Tool | Version used | Install (macOS) |
|---|---|---|
| Docker | 28.x | Docker Desktop |
| kubectl | 1.34 (includes Kustomize) | `brew install kubectl` |
| kind *(local only)* | 0.30+ | `brew install kind` |
| A cluster | any conformant 1.27+ | kind / minikube / Docker Desktop / EKS / GKE / AKS |

The cluster needs an ingress controller (ingress-nginx) for external access and
metrics-server for the HPA. `scripts/kind-setup.sh` installs both on kind.

## 3. Local cluster (kind), step by step

```bash
# 1. Create a 3-node cluster with ingress-nginx and metrics-server (~2 min)
./scripts/kind-setup.sh

# 2. Build the image, load it into kind, apply the local overlay, wait for rollout
./scripts/deploy.sh

# 3. Verify
kubectl -n country-info get pods,svc,hpa,ingress
kubectl -n country-info port-forward svc/country-info-service 8080:80 &
./scripts/smoke-test.sh http://localhost:8080
```

Through the ingress (kind maps host port 8081 to the ingress controller):

```bash
curl -X POST http://localhost:8081/api/v1/countries \
  -H 'Host: country-info.local' -H 'Content-Type: application/json' \
  -d '{"name":"tanzania"}'
```

or add `127.0.0.1 country-info.local` to `/etc/hosts` and browse
`http://country-info.local:8081/swagger-ui.html`.

### Manual equivalent of `deploy.sh`

```bash
docker build -t country-info-service:1.0.0 .
kind load docker-image country-info-service:1.0.0 --name country-info
kubectl apply -k k8s/overlays/local
kubectl -n country-info rollout status statefulset/mysql --timeout=300s
kubectl -n country-info rollout status deployment/country-info-service --timeout=300s
```

## 4. Production

1. **Build and push** an immutable tag (CI does this on every merge to `main`):
   ```bash
   docker build -t ghcr.io/<org>/country-info-service:1.0.1 .
   docker push ghcr.io/<org>/country-info-service:1.0.1
   ```
2. **Provision MySQL** (managed, HA, backups/PITR). Create database `countryinfo` and a
   least-privilege user. Flyway creates the tables on first start.
3. **Create the DB secret** out-of-band (or via External Secrets / Sealed Secrets / Vault):
   ```bash
   kubectl create namespace country-info
   kubectl -n country-info create secret generic country-info-db \
     --from-literal=DB_USERNAME=countryinfo --from-literal=DB_PASSWORD='<strong-password>'
   ```
4. **Set environment values** in `k8s/overlays/production/kustomization.yaml`: DB host/URL,
   ingress host, TLS issuer, image name/tag.
5. **Review and apply**:
   ```bash
   kubectl diff -k k8s/overlays/production
   kubectl apply -k k8s/overlays/production
   kubectl -n country-info rollout status deployment/country-info-service --timeout=300s
   ```
   or `OVERLAY=production IMAGE=ghcr.io/<org>/country-info-service TAG=1.0.1 ./scripts/deploy.sh`.

## 5. Day-2 operations

| Task | Command |
|---|---|
| Release a new version | `kubectl -n country-info set image deployment/country-info-service app=<image>:<tag>` (or re-run `deploy.sh` with `TAG`) |
| Watch a rollout | `kubectl -n country-info rollout status deployment/country-info-service` |
| Roll back | `kubectl -n country-info rollout undo deployment/country-info-service` |
| History | `kubectl -n country-info rollout history deployment/country-info-service` |
| Apply config change | edit overlay, `kubectl apply -k ...`, then `kubectl -n country-info rollout restart deployment/country-info-service` |
| Scale manually | `kubectl -n country-info scale deployment/country-info-service --replicas=5` (HPA will reconcile within its bounds) |
| Change HPA bounds | `kubectl -n country-info patch hpa country-info-service -p '{"spec":{"maxReplicas":20}}'` |
| Enable trace export | add `MANAGEMENT_OPENTELEMETRY_TRACING_EXPORT_OTLP_ENDPOINT` to the ConfigMap, restart |
| Temporarily enable SOAP payload logging | `kubectl -n country-info set env deployment/country-info-service LOGGING_LEVEL_COM_NCBALOOP_COUNTRYINFO_INTEGRATION_SOAP=DEBUG` (remove afterwards) |

## 6. Tear down

```bash
kubectl delete -k k8s/overlays/local     # app + MySQL (PVC is kept)
kubectl -n country-info delete pvc --all # data
kind delete cluster --name country-info  # whole local cluster
```
