#!/usr/bin/env bash
# End-to-end smoke test against a running instance.
# Usage: ./scripts/smoke-test.sh [base-url]   (default http://localhost:8080)
#   For Kubernetes: kubectl -n country-info port-forward svc/country-info-service 8080:80
set -euo pipefail

BASE="${1:-http://localhost:8080}"
API="$BASE/api/v1/countries"
pass=0; fail=0

check() {
  local name="$1" expected="$2" actual="$3"
  if [[ "$actual" == "$expected" ]]; then echo "  PASS  $name ($actual)"; pass=$((pass+1));
  else echo "  FAIL  $name (expected $expected, got $actual)"; fail=$((fail+1)); fi
}

status() { curl -s -o /dev/null -w '%{http_code}' "$@"; }

echo "Smoke testing $BASE"
check "readiness probe"            200 "$(status "$BASE/actuator/health/readiness")"
check "liveness probe"             200 "$(status "$BASE/actuator/health/liveness")"

body=$(curl -s -X POST "$API" -H 'Content-Type: application/json' -d '{"name":"tanzania"}')
id=$(echo "$body" | sed -n 's/.*"id":\([0-9]*\).*/\1/p')
iso=$(echo "$body" | sed -n 's/.*"isoCode":"\([A-Z]*\)".*/\1/p')
check "import 'tanzania' -> ISO"   TZ "$iso"

check "import is idempotent"       200 "$(status -X POST "$API" -H 'Content-Type: application/json' -d '{"name":"TANZANIA"}')"
check "unknown country -> 404"     404 "$(status -X POST "$API" -H 'Content-Type: application/json' -d '{"name":"narnia"}')"
check "invalid body -> 400"        400 "$(status -X POST "$API" -H 'Content-Type: application/json' -d '{"name":""}')"
check "list countries"             200 "$(status "$API?page=0&size=10&sort=name,asc")"
check "get by id"                  200 "$(status "$API/$id")"
check "update"                     200 "$(status -X PUT "$API/$id" -H 'Content-Type: application/json' \
  -d '{"name":"Tanzania","capitalCity":"Dodoma","phoneCode":"255","continentCode":"AF","currencyIsoCode":"TZS","languages":[{"isoCode":"swa","name":"Swahili"}]}')"
check "delete"                     204 "$(status -X DELETE "$API/$id")"
check "get deleted -> 404"         404 "$(status "$API/$id")"

echo "Passed: $pass  Failed: $fail"
[[ $fail -eq 0 ]]
