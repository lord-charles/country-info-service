# Troubleshooting on Kubernetes

All commands assume `NS=country-info` and `APP=deployment/country-info-service`:

```bash
export NS=country-info APP=deployment/country-info-service
```

## 1. First five minutes: triage checklist

```bash
kubectl -n $NS get pods -o wide                       # status, restarts, node
kubectl -n $NS get events --sort-by=.lastTimestamp | tail -30
kubectl -n $NS describe pod <pod>                     # probe failures, OOMKilled, image errors
kubectl -n $NS logs <pod> --tail=200                  # current container
kubectl -n $NS logs <pod> --previous                  # the crashed container
kubectl -n $NS rollout status $APP
kubectl -n $NS get endpoints country-info-service     # are any pods receiving traffic?
kubectl -n $NS top pods                               # CPU / memory (needs metrics-server)
```

Then check the app's own view of itself:

```bash
kubectl -n $NS port-forward svc/country-info-service 8080:80 &
curl -s localhost:8080/actuator/health | jq            # db, circuit breaker, liveness, readiness
curl -s localhost:8080/actuator/circuitbreakers | jq
curl -s localhost:8080/actuator/prometheus | grep -E 'countryinfo_|resilience4j_circuitbreaker_state|hikaricp_connections_pending'
```

## 2. Pod status playbook

| Symptom | Likely cause | How to confirm | Fix |
|---|---|---|---|
| `Init:0/1` for a long time | `wait-for-db` cannot reach MySQL | `kubectl -n $NS logs <pod> -c wait-for-db`; `kubectl -n $NS get pods -l app.kubernetes.io/name=mysql` | Start/fix MySQL; check `DB_HOST`/`DB_PORT` in the ConfigMap and the NetworkPolicy |
| `ErrImagePull` / `ImagePullBackOff` | Wrong image/tag, registry auth, or (kind) image not loaded | `describe pod` -> Events | Fix tag; `kind load docker-image ...`; add `imagePullSecrets` |
| `CreateContainerConfigError` | Missing Secret/ConfigMap key | `describe pod` names the key | `kubectl -n $NS get secret country-info-db -o yaml`; create the missing key |
| `CrashLoopBackOff` | App fails at startup | `logs --previous`, look for `APPLICATION FAILED TO START` | See section 3 |
| `OOMKilled` (Last State) | Heap + metaspace + threads exceed limit | `describe pod` -> `Reason: OOMKilled`; `kubectl top pod` | Raise memory limit, or lower `-XX:MaxRAMPercentage` in `JAVA_TOOL_OPTIONS` |
| `Running` but `0/1 READY` | Readiness failing (usually DB) | `describe pod` -> `Readiness probe failed`; `curl /actuator/health/readiness` | Fix DB connectivity; check Hikari errors in logs |
| Restarts with `Liveness probe failed` | JVM stalled (GC thrash, deadlock) or probe too strict | Events + `logs --previous`; JVM metrics | Thread dump (section 6); increase resources; tune probe `timeoutSeconds` |
| `Pending` | Not enough CPU/memory, or PVC unbound | `describe pod` -> `FailedScheduling`; `kubectl -n $NS get pvc` | Lower requests, add nodes, fix StorageClass |
| Killed during startup | Startup probe budget (150s) too small | Events show `Startup probe failed` | Increase `startupProbe.failureThreshold` or CPU request |

## 3. Startup failures (`APPLICATION FAILED TO START`)

| Log message | Meaning | Fix |
|---|---|---|
| `Communications link failure` / `Connection refused` | DB unreachable | Check MySQL pod, Service DNS (`mysql`), NetworkPolicy egress 3306 |
| `Access denied for user` | Wrong DB credentials | Compare Secret with the DB user; restart pods after changing the Secret |
| `Unknown database 'countryinfo'` | DB not created | Create it (MySQL StatefulSet does this via `MYSQL_DATABASE`) |
| `Validate failed: Migrations have failed validation` / `checksum mismatch` | A migration file was edited after being applied | Never edit applied migrations; add a new `V2__...sql`. In dev only: `flyway repair` |
| `Schema-validation: missing table/column` | Hibernate `validate` disagrees with schema | A migration is missing; add it |
| `Could not bind properties to ...` | Bad config value type (e.g. `3s` where ms is expected) | Fix the ConfigMap/env var; message names the property |

## 4. Runtime errors seen by API clients

Every error body carries `requestId`. Find all log lines for that request:

```bash
kubectl -n $NS logs -l app.kubernetes.io/name=country-info-service --tail=-1 --prefix \
  | grep '<requestId>'
```

(With Loki: `{namespace="country-info"} | json | requestId="<id>"`.)
The same line carries `traceId` to open the trace in Tempo/Jaeger.

| Client sees | `type` suffix | Meaning | What to check |
|---|---|---|---|
| `400` | `validation-error`, `invalid-sort` | Bad input | Response `errors[]` lists the fields |
| `404` | `country-not-found` | Provider does not know the name/ISO | Try the exact English name (e.g. "United States") |
| `404` | `resource-not-found` | No stored record with that id | `GET /api/v1/countries` |
| `409` | `concurrent-modification` | Optimistic-lock conflict | Client should re-`GET` and retry |
| `503` + `Retry-After` | `upstream-unavailable` | Provider down, retries exhausted, circuit open or bulkhead full | Section 5 |
| `504` | `upstream-timeout` | Provider slower than the read timeout (4s) | Section 5 |
| `502` | `upstream-bad-response` | SOAP fault or unparseable payload | Enable SOAP debug logging (section 6) |
| `500` | `internal-error` | Bug | Logs at `ERROR` with stack trace for the `requestId` |
| `200` + `X-Data-Source: local-store` | n/a | Provider down; stored copy served (by design) | Section 5 |

## 5. Upstream (SOAP provider) problems

1. **Is the circuit open?**
   ```bash
   curl -s localhost:8080/actuator/circuitbreakers | jq '.circuitBreakers.countryInfoSoap'
   ```
   `OPEN` means fail-fast is active; it probes again after 30s (`HALF_OPEN`).
2. **Can the cluster reach the provider?** The namespace enforces the *restricted* Pod Security
   Standard, so a plain `kubectl run` is rejected; `scripts/debug-pod.sh` launches a compliant pod:
   ```bash
   ./scripts/debug-pod.sh curlimages/curl curl -s -m 10 -o /dev/null -w '%{http_code}\n' \
     'http://webservices.oorsprong.org/websamples.countryinfo/CountryInfoService.wso?WSDL'
   ```
   - Times out: egress blocked (NetworkPolicy, firewall, proxy) or provider down.
   - Works: check app logs for `Upstream SOAP call failed` and its `errorType`.
3. **Latency**: `countryinfo_soap_client_seconds` histogram; raise `countryinfo.soap.read-timeout` only if the provider is legitimately slow.
4. **Force-close the circuit** after a confirmed recovery: restart pods (`kubectl -n $NS rollout restart $APP`) or wait for half-open probes.

## 6. Deeper debugging

| Need | Command |
|---|---|
| SOAP request/response payloads | `kubectl -n $NS set env $APP LOGGING_LEVEL_COM_NCBALOOP_COUNTRYINFO_INTEGRATION_SOAP=DEBUG` (rolls pods; remove with `...SOAP-`) |
| Change log level without restart (one pod) | `curl -X POST localhost:8080/actuator/loggers/com.ncbaloop.countryinfo -H 'Content-Type: application/json' -d '{"configuredLevel":"DEBUG"}'` |
| Thread dump (JSON) | `curl -s localhost:8080/actuator/threaddump -H 'Accept: application/json' \| jq` |
| Thread dump (to logs) | `kubectl -n $NS exec <pod> -c app -- kill -3 1` then `kubectl -n $NS logs <pod> -c app` |
| Heap / GC | `curl -s localhost:8080/actuator/metrics/jvm.memory.used \| jq`, `jvm.gc.pause` |
| Shell with tools next to the app | `kubectl -n $NS debug -it <pod> --image=busybox:1.36 --target=app --profile=restricted` |
| DNS resolution | `./scripts/debug-pod.sh busybox:1.36 nslookup mysql` |
| Query the DB | `kubectl -n $NS exec -it mysql-0 -- sh -c 'mysql -ucountryinfo -p"$MYSQL_PASSWORD" countryinfo -e "SELECT id,iso_code,name FROM country_info"'` |
| Effective config | `kubectl -n $NS get configmap country-info-config -o yaml`; `kubectl -n $NS exec <pod> -c app -- env \| sort` |
| Is the Service routing? | `kubectl -n $NS get endpointslices -l kubernetes.io/service-name=country-info-service` |
| Ingress issues | `kubectl -n ingress-nginx logs deploy/ingress-nginx-controller --tail=100`; `kubectl -n $NS describe ingress country-info-service` |
| HPA not scaling | `kubectl -n $NS describe hpa country-info-service`; `<unknown>` targets mean metrics-server is missing |

## 7. Performance

| Symptom | Check | Action |
|---|---|---|
| High p95 on `POST` | `countryinfo_soap_client_seconds`, cache hit ratio (`cache_gets_total`) | Expected on cold cache; warm-up or raise TTL |
| High p95 on `GET` | `hikaricp_connections_pending`, slow query log | Raise `DB_POOL_MAX_SIZE` (keep `replicas x pool < max_connections`), add indexes |
| `503` with bulkhead full | `resilience4j_bulkhead_available_concurrent_calls` at 0 | Scale out (HPA) or raise `max-concurrent-calls` if upstream allows |
| CPU throttling | `container_cpu_cfs_throttled_seconds_total` | Raise CPU limit/request |

## 8. Recovery

```bash
kubectl -n $NS rollout undo $APP                    # bad release
kubectl -n $NS rollout restart $APP                 # stuck pods / config change
kubectl -n $NS delete pod <pod>                     # single bad pod (Deployment replaces it)
```
