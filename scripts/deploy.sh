#!/usr/bin/env bash
# Builds the image, makes it available to the cluster, applies the Kustomize overlay and waits
# for a healthy rollout.
#
# Usage:
#   ./scripts/deploy.sh                      # local overlay, kind cluster "country-info"
#   OVERLAY=production IMAGE=ghcr.io/org/country-info-service TAG=1.0.1 ./scripts/deploy.sh
set -euo pipefail

cd "$(dirname "$0")/.."

OVERLAY="${OVERLAY:-local}"
IMAGE="${IMAGE:-country-info-service}"
TAG="${TAG:-1.0.0}"
NAMESPACE="country-info"
KIND_CLUSTER="${KIND_CLUSTER:-country-info}"

echo "==> Building ${IMAGE}:${TAG}"
docker build -t "${IMAGE}:${TAG}" .

if [[ "$OVERLAY" == "local" ]]; then
  echo "==> Loading image into kind cluster '${KIND_CLUSTER}'"
  kind load docker-image "${IMAGE}:${TAG}" --name "${KIND_CLUSTER}"
else
  echo "==> Pushing ${IMAGE}:${TAG}"
  docker push "${IMAGE}:${TAG}"
  (cd "k8s/overlays/${OVERLAY}" && kustomize edit set image "country-info-service=${IMAGE}:${TAG}")
fi

echo "==> Validating manifests"
kubectl kustomize "k8s/overlays/${OVERLAY}" > /dev/null

echo "==> Applying overlay '${OVERLAY}'"
kubectl apply -k "k8s/overlays/${OVERLAY}"

if [[ "$OVERLAY" == "local" ]]; then
  # The local overlay pins 1.0.0; roll to whatever TAG was just built and loaded.
  kubectl -n "$NAMESPACE" set image deployment/country-info-service "app=${IMAGE}:${TAG}"
fi

if kubectl -n "$NAMESPACE" get statefulset mysql >/dev/null 2>&1; then
  echo "==> Waiting for MySQL"
  kubectl -n "$NAMESPACE" rollout status statefulset/mysql --timeout=300s
fi

echo "==> Waiting for application rollout"
if ! kubectl -n "$NAMESPACE" rollout status deployment/country-info-service --timeout=300s; then
  echo "Rollout did not complete. Recent events and logs:" >&2
  kubectl -n "$NAMESPACE" get pods -o wide >&2
  kubectl -n "$NAMESPACE" get events --sort-by=.lastTimestamp | tail -20 >&2
  kubectl -n "$NAMESPACE" logs deployment/country-info-service --tail=50 >&2 || true
  echo "Roll back with: kubectl -n $NAMESPACE rollout undo deployment/country-info-service" >&2
  exit 1
fi

kubectl -n "$NAMESPACE" get pods,svc,hpa,ingress
echo "==> Deployed. Smoke test: ./scripts/smoke-test.sh"
