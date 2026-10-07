#!/usr/bin/env bash
# Creates a local multi-node kind cluster with ingress-nginx and metrics-server (for the HPA).
# Usage: ./scripts/kind-setup.sh [cluster-name]
set -euo pipefail

CLUSTER_NAME="${1:-country-info}"

command -v kind >/dev/null || { echo "kind is required: https://kind.sigs.k8s.io (brew install kind)"; exit 1; }
command -v kubectl >/dev/null || { echo "kubectl is required"; exit 1; }

if kind get clusters | grep -qx "$CLUSTER_NAME"; then
  echo "Cluster '$CLUSTER_NAME' already exists"
else
  cat <<EOF | kind create cluster --name "$CLUSTER_NAME" --config=-
kind: Cluster
apiVersion: kind.x-k8s.io/v1alpha4
nodes:
  - role: control-plane
    kubeadmConfigPatches:
      - |
        kind: InitConfiguration
        nodeRegistration:
          kubeletExtraArgs:
            node-labels: "ingress-ready=true"
    extraPortMappings:
      - { containerPort: 80, hostPort: 8081, protocol: TCP }
  - role: worker
  - role: worker
EOF
fi

kubectl config use-context "kind-$CLUSTER_NAME"

echo "Installing ingress-nginx..."
kubectl apply -f https://raw.githubusercontent.com/kubernetes/ingress-nginx/controller-v1.12.1/deploy/static/provider/kind/deploy.yaml

echo "Installing metrics-server (needed by the HorizontalPodAutoscaler)..."
kubectl apply -f https://github.com/kubernetes-sigs/metrics-server/releases/latest/download/components.yaml
kubectl -n kube-system patch deployment metrics-server --type=json \
  -p='[{"op":"add","path":"/spec/template/spec/containers/0/args/-","value":"--kubelet-insecure-tls"}]' || true

kubectl -n ingress-nginx wait --for=condition=ready pod -l app.kubernetes.io/component=controller --timeout=180s

echo "Cluster '$CLUSTER_NAME' is ready. Next: ./scripts/deploy.sh"
