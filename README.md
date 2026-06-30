# Fleet Copilot

[![CI](https://github.com/fabrizio-ferrentino/fleet-copilot/actions/workflows/ci.yml/badge.svg)](https://github.com/fabrizio-ferrentino/fleet-copilot/actions/workflows/ci.yml)

An AI agent that troubleshoots a simulated IoT fleet in natural language. Ask *"which devices
have been offline since yesterday?"* or *"why did device 42 stop transmitting?"* and the agent
investigates by calling real APIs over real data, then answers with evidence.

Under the hood: a Python simulator drives 200+ devices over MQTT, a Spring Boot platform ingests
telemetry into TimescaleDB and exposes a fleet REST API, and an LLM-powered agent uses that API
as its toolbox.

![Fleet Copilot demo](docs/demo.gif)

### Example questions

- *Which devices are offline right now?*
- *Is anything anomalous in the last hour?*
- *Why did dev-042 stop transmitting?*
- *How many devices are online versus in error?*
- *Show me the recent error events for dev-007.*

## Project status

| Milestone | Scope | Status |
|-----------|-------|--------|
| M1 | Data flows: simulator → MQTT → ingestion → TimescaleDB | ✅ done |
| M2 | Fleet REST API, fault injection, anomaly rules | ✅ done |
| M3 | First agent (terminal) with tool calling | ✅ done |
| M4 | Full product: FastAPI agent, React chat UI, one-command compose | ✅ done |
| M5 | CI, integration tests, README polish, measured numbers | ✅ done |

## Architecture

```
┌────────────┐   MQTT    ┌───────────┐   MQTT    ┌──────────────────┐
│ Simulator  │ ────────► │ Mosquitto │ ────────► │ Platform          │
│ (Python)   │  publish  │ (broker)  │ subscribe │ (Spring Boot)     │
│ N devices  │           └───────────┘           │ ingestion + REST  │
│ + faults   │                                   └───────┬──────────┘
└────────────┘                                           │ JDBC
                                                         ▼
                                              ┌─────────────────────┐
                                              │ PostgreSQL +        │
                                              │ TimescaleDB         │
                                              └─────────────────────┘
                                                         ▲
                                                         │ REST (tools)
┌────────────┐   HTTP    ┌────────────────┐              │
│ Chat UI    │ ────────► │ Agent (Python) │ ─────────────┘
│ (React)    │  /ask     │ LLM + tool     │
└────────────┘           │ calling loop   │
                         └────────────────┘
```

See [docs/architecture.md](docs/architecture.md) for design notes.

## Quickstart

Requires Docker only.

```bash
cp .env.example .env        # then set GEMINI_API_KEY inside .env
docker compose up --build
```

Open **http://localhost:5173**, inject a fault (see below) and ask the agent about it.
The full fleet runs in containers: simulator (300 devices) → Mosquitto → platform → TimescaleDB,
with the agent on :8000 and the chat UI on :5173.

Watch the data arrive:

```bash
docker exec fleet-postgres psql -U fleet -d fleet \
  -c "SELECT count(*) FROM telemetry" \
  -c "SELECT id, last_seen, last_status, last_battery_pct FROM devices LIMIT 5"
```

> Postgres is published on host port **5433** (container port stays 5432) so it never
> clashes with a locally installed PostgreSQL. Connect GUI tools to `localhost:5433`,
> database `fleet`, user `fleet`.

Configuration lives in environment variables — see [.env.example](.env.example).
Try feeding the broker garbage; ingestion logs the reason and keeps running:

```bash
docker exec fleet-mosquitto mosquitto_pub -t fleet/dev-bad/telemetry -m '{not json'
```

## API

The platform exposes the fleet on `http://localhost:8080`. All timestamps are UTC ISO-8601;
time windows accept `30s`, `15m`, `24h`, `7d` (max 7d).

```bash
# fleet totals: online, offline, warning, error
curl "http://localhost:8080/api/fleet/status"

# device list, filterable with status=online|offline|ok|warning|error
curl "http://localhost:8080/api/devices?status=offline"

# current state of one device
curl "http://localhost:8080/api/devices/dev-042"

# history, newest first (optional ISO-8601 from/to, limit 1..1000)
curl "http://localhost:8080/api/devices/dev-042/telemetry?limit=20"

# recent ERROR events: total count in the window plus the latest occurrences
curl "http://localhost:8080/api/devices/dev-042/errors?window=24h"

# rule-based findings: SILENT, BATTERY_DROP, GPS_JUMP, HIGH_TEMPERATURE
curl "http://localhost:8080/api/anomalies?window=1h"
```

## Breaking the fleet (fault injection)

Faults can be enabled at startup (`fleet-simulator --fault dev-042:silent`, or env
`FAULTS=dev-042:silent,dev-007:error_burst`) and injected at runtime over MQTT:

```bash
# faults: silent, battery_drain, gps_drift, error_burst
docker exec fleet-mosquitto mosquitto_pub -t fleet/control -m '{"deviceId":"dev-042","fault":"silent"}'

# recover the device
docker exec fleet-mosquitto mosquitto_pub -t fleet/control -m '{"deviceId":"dev-042","fault":"clear"}'
```

Demo tip: run the platform with `OFFLINE_THRESHOLD=40s` and a silenced device appears in
`/api/anomalies` (rule `SILENT`) within a minute instead of ten.

## Asking the agent

The agent (Gemini with function calling) investigates by calling the REST API as tools —
`get_fleet_status`, `list_devices`, `get_device`, `get_device_history`, `get_device_errors`,
`detect_anomalies` — then answers with evidence. At most 6 tool calls per question, and every
answer carries the tool trace (the chat UI shows it as a collapsible "how I investigated" panel):

```
how I investigated (2 tool calls):
  1. get_fleet_status {} -> {"total":50,"online":47,"offline":3,...}
  2. list_devices {"status":"offline"} -> 3 results: dev-031, dev-032, dev-033
```

The chat UI streams the investigation **live** over Server-Sent Events — each tool call shows up
in the "how I investigated" panel as it happens, then the answer. Provider hiccups (e.g. a Gemini
rate limit) are retried with backoff and, if they persist, surface as a clean message and a `503`,
never a raw `500`.

Besides the UI, the agent is also available as:

```bash
# streaming (SSE): tool calls stream live, then the answer — this is what the chat UI uses
curl -N -X POST localhost:8000/ask/stream -H "Content-Type: application/json" \
  -d '{"question":"How many devices are online versus in error?"}'

# non-streaming JSON ({"answer","toolTrace"}) — the stable contract for scripts
curl -X POST localhost:8000/ask -H "Content-Type: application/json" \
  -d '{"question":"Which devices are offline right now?"}'

# terminal CLI (from agent/, after pip install -e .)
fleet-agent "Which devices are offline right now?"
```

## Development mode (services on the host)

Run only the infrastructure in Docker and the services you are working on locally:

```bash
docker compose up -d mosquitto postgres
cd platform && ./mvnw spring-boot:run                  # API on :8080
cd simulator && pip install -e . && fleet-simulator    # in its own venv
cd agent && pip install -e . && fleet-agent            # in its own venv
cd ui && npm install && npm run dev                    # UI on :5173
```

## Design decisions

The reasoning behind each choice lives in [docs/architecture.md](docs/architecture.md); the four
that matter most:

- **Why TimescaleDB (a hypertable) for telemetry.** Telemetry is append-only and every query is
  time-bounded ("last hour of dev-042", "errors in 24h"). A hypertable transparently partitions
  rows into time chunks, so those queries touch only recent chunks instead of one ever-growing
  table — and `first()`/`last()` make the battery-window rule a single scan. At ~500 msg/s a plain
  table's indexes would degrade within days. The device *snapshot* lives in a plain 300-row table
  because fleet-status questions never need history.
- **Why rule-based anomalies, not ML.** The four faults have crisp physical definitions (silent >
  threshold, battery drop > 20 pts/h, implied speed > 200 km/h, temp > 70 °C). Thresholds are
  explainable, unit-testable with a fixed clock, need no training data, and produce the exact
  evidence the agent cites. ML would add opacity and a data pipeline for zero benefit here.
- **Why the agent calls REST tools instead of querying the DB.** The API is the same validated,
  compact contract a human operator uses: the platform keeps owning query shaping and safety, the
  agent gets no SQL surface to misuse or leak, and every investigation step is a meaningful HTTP
  call we can show as a trace. It also keeps the agent decoupled from the schema.
- **Why max 6 tool calls.** Bounds cost and latency and forces decisive investigation. The cap is
  enforced in the loop, not trusted to the model: past 6, calls get an `{"error":"budget"}` payload
  that pushes the model to answer from what it has; a separate round cap stops a runaway model.

## Measured numbers

**Sustained ingestion ≈ 500 messages/second** (single platform instance, one transaction per
message), on the dev machine used during development.

*How it was measured:* run `mosquitto` + `postgres` in Docker and the platform locally, point the
simulator at the broker at a target rate, let it warm up 15 s, then count `telemetry` rows over a
fixed 30 s window:

```bash
docker compose up -d mosquitto postgres
cd platform && ./mvnw spring-boot:run            # in another terminal
cd simulator && fleet-simulator --devices 500 --interval 1   # ~500 msg/s, another terminal
# count, wait 30s, count again:
docker exec fleet-postgres psql -U fleet -d fleet -tAc "SELECT count(*) FROM telemetry"
```

At a 500 msg/s publish rate the platform kept pace (≈499 msg/s ingested, 0 failed publishes); at
1000 msg/s it sustained ≈490 msg/s, i.e. the single-consumer + per-message-transaction design
tops out around 500 msg/s here. The bottleneck and how to lift it (batching, multiple consumers)
are noted under future work.

## Testing

```bash
cd platform && ./mvnw verify   # JUnit unit tests + Testcontainers integration tests + Spotless
cd agent && pip install -e ".[dev]" && pytest   # loop, tools, /ask contract; golden tests too
cd ui && npm ci && npm run build                # typecheck + production build
```

- **Platform unit tests** cover the payload parser, the four anomaly rules (fixed-clock) and the
  device read model.
- **Testcontainers integration tests** spin up real PostgreSQL/TimescaleDB and Mosquitto, publish
  over MQTT and assert the reading lands in the DB, a malformed message is skipped without
  crashing ingestion, and a silenced device surfaces as a `SILENT` anomaly.
- **Agent tests** check the loop mechanics (6-call budget, error-as-data), the `/ask` contract and
  the `/ask/stream` SSE events with a fake provider, plus provider error mapping (a Gemini
  rate limit becomes a retried, then graceful, `LlmError`); the **golden tests** drive the real LLM
  with a mocked tool layer to assert each question triggers the right tool, and skip automatically
  when `GEMINI_API_KEY` is absent so CI stays green without a secret.
- **CI** (GitHub Actions) runs all of the above on every push and pull request.

## Future work / non-goals

By design (see the project spec): no auth, no multi-tenancy, no Kubernetes, no Kafka, no real
hardware, no ML, no RAG, single LLM provider only.

Production hardening worth doing next: **idempotent ingestion** (dedupe on `(device_id, ts)` to
absorb MQTT QoS-1 redeliveries), **dead-letter handling** for malformed messages instead of
log-and-drop, **batched/multi-consumer ingestion** to push past ~500 msg/s, and
**observability** (metrics, tracing) on the ingestion path.
