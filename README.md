# APM Trace Explorer

Application performance monitoring for microservices: OpenTelemetry span ingestion, trace assembly,
per-service/per-endpoint RED metrics with HDR-histogram percentiles, a D3 service map / latency heatmap /
trace waterfall UI, rolling-baseline anomaly detection, and an MCP server so an AI assistant can triage
incidents (`find_slow_endpoints`, `get_trace`, `compare_deploys`, …).

## Design

```
                        OTLP/HTTP JSON (POST /v1/traces)
  instrumented services ───────────────┐
  trace generator (10-service shop) ───┤
                                       v
                            ┌─────────────────────┐
                            │  Spring Boot backend │
                            │  Otlp.decode         │
                            │  IngestService ──────┼──> SpanStore ── Redis (external) / in-memory (default)
                            │       │              ├──> MetadataStore ── PostgreSQL / in-memory
                            │       v              │      (deploys, anomalies)
                            │  MetricsAggregator   │
                            │  HDR histograms per  │
                            │  endpoint/edge/ver., │
                            │  1-min buckets       │
                            │       │              │
                            │  AnomalyDetector     │  recent buckets vs median-of-buckets baseline
                            └──────────┬───────────┘
                       REST /api/*   │        MCP over stdio (tools/call)
                  ┌──────────────────┴───┐   ┌────────────────────────┐
                  │ React + D3 frontend │   │ mcp-server (Node, 0 deps)│--> AI assistant
                  │ map/heatmap/waterfall│   └────────────────────────┘
                  └────────────────────┘
```

| Piece | Choice | Why / trade-off |
|---|---|---|
| Language/runtime | Java 21, Spring Boot 3.3 | Records, virtual-thread-friendly, actuator probes for k8s |
| Ingest | OTLP/HTTP **JSON** only (`POST /v1/traces`) | Human-debuggable; no protobuf/gRPC codegen. Binary OTLP is the upgrade path |
| Latency percentiles | HdrHistogram (µs, 2 sig. digits), 1-min buckets, 180-bucket retention | Mergeable, bounded memory; bucket-start = event time so backfill lands correctly |
| Span store | Redis lists `trace:{id}` + TTL (compose/k8s), in-memory LRU for tests/local | Fast trace fetch; no span search/index — see Limitations |
| Metadata | PostgreSQL (`deploys`, `anomalies`, `schema.sql`), in-memory fake otherwise | Low volume, relational fits |
| Anomaly detection | Current 2 buckets vs median of previous 15 per-bucket p95/error rates; cooldown | Median resists the incident itself contaminating the baseline |
| Simulator | Deterministic-seeded 10-service e-commerce call trees, log-normal latency, fault windows | Reproducible demo incident: payment 2.4.0 at 6× latency, inventory 25% errors |
| Frontend | React 18 + TypeScript + D3 7, Vite | D3 force service map, heatmap cells, waterfall geometry in pure tested functions (`lib.ts`) |
| MCP server | Node stdio JSON-RPC, zero dependencies, proxies REST | Any MCP client can triage without new backend code |
| Deploy | Dockerfiles, docker-compose (Postgres+Redis), `k8s/apm.yaml` (probes, HPA) | Same `external` profile in compose and k8s |

## Run

Backend only (in-memory, nothing else needed), Java 21:

```bash
cd backend && ./mvnw spring-boot:run          # http://localhost:8080
curl -XPOST 'http://localhost:8080/api/simulate?minutes=30&rps=20&incidentMinutes=3'
```

Full stack:

```bash
docker compose up --build                     # UI http://localhost:5173, API :8080
docker compose --profile demo run generator   # or click "Simulate incident" in the UI
cd frontend && npm ci && npm run dev          # dev UI on :5173, proxies /api to :8080
```

Generator against a running backend:

```bash
cd backend && ./mvnw exec:java -Dexec.mainClass=dev.apm.sim.GeneratorMain \
  -Dexec.args="--minutes 30 --rps 20"         # add --live to keep streaming
```

MCP server (point your MCP client at it; `APM_BACKEND` overrides the backend URL):

```bash
cd mcp-server && npm start                    # stdio; tools: find_slow_endpoints, find_error_endpoints,
                                              # get_trace, compare_deploys, list_anomalies, get_service_map
```

Kubernetes: `kubectl apply -f k8s/apm.yaml` (build/push the two images first, or point them at a registry).

## Test

```bash
cd backend && ./mvnw test                     # JUnit: OTLP codec, assembler, HDR metrics, anomaly detector, full API
cd frontend && npm test                       # Vitest: waterfall/heatmap geometry, health thresholds
cd mcp-server && npm test                     # node:test: tool routing + protocol handling
```

Verified on this machine: backend JUnit suite passes, frontend 6/6 Vitest tests pass, MCP 4/4 tests pass,
`npm run build` (tsc + vite) succeeds.

## Benchmark (laptop sanity run)

`IngestBenchmark` measures in-process OTLP-JSON parse + ingest and query latency against the in-memory
stores (no HTTP, Redis or PostgreSQL in the loop — it is a ceiling, not an end-to-end number).
Scaled down from the 100k-trace default to 50k traces on this laptop:

```bash
cd backend && ./mvnw exec:java -Dexec.args="50000 8 1000"
```

```
apm-trace-explorer ingest benchmark: 50000 traces, 8 threads, 1000 queries/type, JDK 21.0.13.0.3, 15 cores

INGEST  234,087 spans in 776 requests (56.5 MB OTLP JSON) in 0.12 s
        2,034,544 spans/s   434,570 traces/s   491.4 MB/s

QUERY                          p50 ms     p99 ms     max ms
endpoints (all, 15m)            0.273      0.479      5.835
service map (15m)               0.422      0.592      3.307
heatmap (1 service, 30m)        0.089      0.357      0.415
get trace + assemble            0.002      0.015      0.376

ANOMALY detection pass: 3.5 ms, 15 findings (incident: payment-service 2.4.0 6x slower, inventory-service 25% errors)
  latency  payment-service    Charge           p95   66.0 ->  399.4 ms  err   0.0% ->   0.0%
  errors   inventory-service  GetStock         p95    8.4 ->    8.3 ms  err   0.2% ->  28.4%
  errors   inventory-service  Reserve          p95   13.7 ->   14.1 ms  err   0.0% ->  26.0%
```

Treat the ingest figure as an in-process upper bound on an idle laptop: a single HTTP hop, TLS, Redis
writes and multi-minute sustained load will be far slower. Re-run with the default `100000` traces
(or more) on your own hardware before quoting anything.

## Limitations (honest)

- **OTLP JSON only** — no protobuf/gRPC receiver, no metrics/logs signals, no sampling or tail-sampling.
- **Metrics live in backend memory** (HDR buckets), even in the `external` profile: a backend restart or
  a second replica loses/diverges RED data. Redis/Postgres only hold spans/metadata. A real deployment
  would push buckets to ClickHouse/Prometheus.
- **No span search**: traces are fetched by id or "recent"; endpoint exemplars link to slow/error traces,
  but there is no attribute filtering, auth, multi-tenancy, or alerting/notification channel.
- **Anomaly detector is a heuristic** (fixed ratio/delta thresholds + cooldown), not statistical process
  control; it needs ≥30 samples and ≥8 baseline buckets before it can fire.
- **docker-compose / k8s paths are written but not verified end-to-end in this environment** (no Docker
  daemon was available); the in-memory profile and all tests are verified. The compose `generator`
  service relies on Spring Boot's `PropertiesLauncher` to run `GeneratorMain` from the app jar.
- Retention is fixed (180 one-minute buckets in memory; 2 h trace TTL in Redis), no downsampling.

## Layout

```
backend/    Spring Boot app: ingest, metrics, anomaly, API, simulator, benchmark, JUnit tests
frontend/   React + D3 UI (service map, heatmap, waterfall), Vitest tests
mcp-server/ Zero-dependency Node MCP server + tests
k8s/        Namespace, Deployments, Services, probes, HPAs
docker-compose.yml   Postgres + Redis + backend + frontend (+ demo generator profile)
```
