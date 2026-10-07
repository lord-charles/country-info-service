# Country Info Service

A Spring Boot microservice that turns a legacy **SOAP** country-information provider into a
clean **REST** API, stores the results in **MySQL**, and runs production-ready on
**Kubernetes**.

```
POST {"name":"tanzania"}
  -> sentence case "Tanzania"
  -> SOAP CountryISOCode("Tanzania")  = "TZ"
  -> SOAP FullCountryInfo("TZ")       = capital, phone code, currency, flag, languages ...
  -> upsert into MySQL (country_info + language)
  -> 201 Created
```

| | |
|---|---|
| Stack | Java 17, Spring Boot 4.1, Spring-WS 5 (JAXB from WSDL), Spring Data JPA, Flyway, MySQL 8.4 |
| Resilience | Resilience4j retry + circuit breaker + bulkhead, HTTP timeouts, Caffeine cache, stored-data fallback |
| Observability | ECS JSON logs with trace/request ids, Prometheus metrics, OpenTelemetry tracing, health probes |
| Delivery | Multi-stage Docker image, docker-compose, Kustomize manifests (base + overlays), GitHub Actions CI |
| Tests | 43 tests: unit, MockMvc, real-HTTP SOAP transport, end-to-end with MySQL (Testcontainers) |

**Docs:** [Architecture and design decisions](docs/ARCHITECTURE.md) ·
[Kubernetes deployment guide](docs/DEPLOYMENT.md) ·
[Kubernetes troubleshooting guide](docs/TROUBLESHOOTING.md) ·
[SoapUI walkthrough](docs/SOAPUI.md)

---

## API

Base path `/api/v1/countries`. Interactive docs: `http://localhost:8080/swagger-ui.html`.

| Method | Path | Description | Success | Errors |
|---|---|---|---|---|
| `POST` | `/api/v1/countries` | Import a country by name (body `{"name":"kenya"}`) | `201` + `Location` (new), `200` (re-import) | `400`, `404`, `502`, `503`, `504` |
| `GET` | `/api/v1/countries?page=0&size=20&sort=name,asc` | List stored countries (paginated, max size 100) | `200` | `400` |
| `GET` | `/api/v1/countries/{id}` | Get one country | `200` | `404` |
| `PUT` | `/api/v1/countries/{id}` | Replace a country's details and languages | `200` | `400`, `404`, `409` |
| `DELETE` | `/api/v1/countries/{id}` | Delete a country (and its languages) | `204` | `404` |

Example response:

```json
{
  "id": 1, "isoCode": "TZ", "name": "Tanzania", "capitalCity": "Dar es Salaam",
  "phoneCode": "255", "continentCode": "AF", "currencyIsoCode": "TZS",
  "countryFlag": "http://www.oorsprong.org/WebSamples.CountryInfo/Flags/Tanzania.jpg",
  "languages": [{ "isoCode": "swa", "name": "Swahili" }],
  "version": 0, "createdAt": "2026-10-07T12:42:58.128Z", "updatedAt": "2026-10-07T12:42:58.128Z"
}
```

Errors use RFC 9457 `application/problem+json`:

```json
{
  "type": "urn:problem-type:country-info:country-not-found",
  "title": "Country not found", "status": 404,
  "detail": "No country found matching 'Narnia'",
  "instance": "/api/v1/countries",
  "timestamp": "2026-10-07T12:42:59.303Z",
  "requestId": "b4966a28-28d9-48df-9bf0-aece703a5c23"
}
```

Response headers: `X-Request-ID` on every response (quote it in support requests);
`X-Data-Source: local-store` when the provider was down and the stored copy was served.

## Running

### Option A: Docker Compose (fastest, needs only Docker)

```bash
docker compose up --build -d
./scripts/smoke-test.sh                 # 11 end-to-end checks
open http://localhost:8080/swagger-ui.html
docker compose down -v                  # stop and remove data
```

### Option B: Local JVM (needs Java 17 and a MySQL)

```bash
docker compose up -d mysql              # or point DB_URL / DB_USERNAME / DB_PASSWORD at your own MySQL
./mvnw spring-boot:run
```

### Option C: Kubernetes

```bash
./scripts/kind-setup.sh                 # local 3-node kind cluster + ingress-nginx + metrics-server
./scripts/deploy.sh                     # build, load image, kubectl apply -k, wait for rollout
kubectl -n country-info port-forward svc/country-info-service 8080:80 &
./scripts/smoke-test.sh
```

Full guide: [docs/DEPLOYMENT.md](docs/DEPLOYMENT.md).

## Testing

```bash
./mvnw test        # needs Docker running (Testcontainers starts MySQL 8.4)
```

| Suite | What it proves |
|---|---|
| `CountryNameFormatterTest` | Sentence/title case rules, including multi-word names |
| `CountryInfoServiceTest` | Title-case fallback, idempotent upsert, not-found, stale fallback when upstream is down |
| `CountryControllerTest` | HTTP contract: status codes, `Location`, validation errors, problem details, request id |
| `SoapClientTransportTest` | Real HTTP to an in-process SOAP server: marshalling, in-band not-found, read timeout |
| `CountryInfoIntegrationTest` | Full stack with real MySQL: CRUD lifecycle, caching, retry, circuit breaker, stale fallback, probes, metrics |

Manual testing: import [`postman/country-info-service.postman_collection.json`](postman/country-info-service.postman_collection.json)
into Postman, or use curl:

```bash
curl -i -X POST localhost:8080/api/v1/countries -H 'Content-Type: application/json' -d '{"name":"tanzania"}'
curl -s 'localhost:8080/api/v1/countries?sort=name,asc' | jq
curl -s localhost:8080/api/v1/countries/1 | jq
curl -s -X PUT localhost:8080/api/v1/countries/1 -H 'Content-Type: application/json' \
  -d '{"name":"Tanzania","capitalCity":"Dodoma","languages":[{"isoCode":"swa","name":"Swahili"}]}' | jq
curl -i -X DELETE localhost:8080/api/v1/countries/1
```

## Configuration

All settings are environment variables (12-factor); defaults in `src/main/resources/application.yml`.

| Variable | Default | Purpose |
|---|---|---|
| `DB_URL` | `jdbc:mysql://localhost:3306/countryinfo...` | JDBC URL |
| `DB_USERNAME` / `DB_PASSWORD` | `countryinfo` / `countryinfo` | DB credentials (Secret in Kubernetes) |
| `DB_POOL_MAX_SIZE` | `10` | Hikari pool size per pod |
| `COUNTRYINFO_SOAP_ENDPOINT` | oorsprong endpoint | SOAP provider URL |
| `COUNTRYINFO_SOAP_CONNECT_TIMEOUT` / `_READ_TIMEOUT` | `2s` / `4s` | Upstream timeouts |
| `LOGGING_STRUCTURED_FORMAT_CONSOLE` | *(plain text)* | `ecs` for JSON logs |
| `TRACING_SAMPLING_PROBABILITY` | `1.0` | Trace sampling ratio |
| `MANAGEMENT_OPENTELEMETRY_TRACING_EXPORT_OTLP_ENDPOINT` | *(unset)* | OTLP collector for traces |

## Operational endpoints

| Endpoint | Purpose |
|---|---|
| `/actuator/health/liveness`, `/actuator/health/readiness` | Kubernetes probes |
| `/actuator/health` | Detailed health incl. DB and circuit breaker |
| `/actuator/prometheus` | Metrics scrape |
| `/actuator/circuitbreakers`, `/actuator/caches` | Resilience and cache state |
| `/actuator/loggers`, `/actuator/threaddump` | Runtime debugging (not exposed via ingress) |

## Project layout

```
src/main/java/com/ncbaloop/countryinfo
├── config/            SOAP client wiring, cache config, typed properties
├── integration/       CountryInfoClient port + CountryDetails (anti-corruption layer)
│   └── soap/          Spring-WS adapter with retry/circuit breaker/bulkhead/cache
├── model/             JPA entities: CountryInfo, Language
├── repository/        Spring Data repositories
├── service/           Use cases, name normalisation, mapping
├── exception/         Domain exceptions
└── web/               Controller, DTOs, global error handler, request-id filter
src/main/resources
├── wsdl/              Checked-in WSDL (JAXB classes generated at build time)
└── db/migration/      Flyway migrations
k8s/                   Kustomize base, in-cluster MySQL, local and production overlays
scripts/               kind-setup, deploy, smoke-test, debug-pod
docs/                  Architecture, deployment, troubleshooting, SoapUI
```
