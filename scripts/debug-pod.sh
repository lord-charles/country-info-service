#!/usr/bin/env bash
# Runs a throwaway, Pod-Security-"restricted"-compliant debug pod in the app namespace.
# The namespace enforces the restricted profile, so a plain `kubectl run` is rejected.
#
# Usage:
#   ./scripts/debug-pod.sh curlimages/curl curl -s -m 10 http://webservices.oorsprong.org
#   ./scripts/debug-pod.sh busybox:1.36 nslookup mysql
set -euo pipefail

NS="${NS:-country-info}"
IMAGE="$1"; shift
NAME="debug-$(date +%s)"

ARGS_JSON=$(printf '%s\n' "$@" | python3 -c 'import json,sys; print(json.dumps([l.rstrip("\n") for l in sys.stdin]))')

kubectl -n "$NS" run "$NAME" --rm -i --restart=Never --image="$IMAGE" --overrides="$(cat <<JSON
{
  "spec": {
    "securityContext": { "runAsNonRoot": true, "runAsUser": 65534, "seccompProfile": { "type": "RuntimeDefault" } },
    "containers": [{
      "name": "$NAME",
      "image": "$IMAGE",
      "command": $ARGS_JSON,
      "securityContext": { "allowPrivilegeEscalation": false, "capabilities": { "drop": ["ALL"] } }
    }]
  }
}
JSON
)"
