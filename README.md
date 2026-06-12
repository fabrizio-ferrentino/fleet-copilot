# Fleet Copilot

An AI agent that troubleshoots a simulated IoT fleet in natural language. Ask *"which devices
have been offline since yesterday?"* or *"why did device 42 stop transmitting?"* and the agent
investigates by calling real APIs over real data, then answers with evidence.

Under the hood: a Python simulator drives 300 devices over MQTT, a Spring Boot platform ingests
telemetry into TimescaleDB and exposes a fleet REST API, and an LLM-powered agent uses that API
as its toolbox.

## Project status

| Milestone | Scope | Status |
|-----------|-------|--------|
| M1 | Data flows: simulator → MQTT → ingestion → TimescaleDB | ✅ done |
| M2 | Fleet REST API, fault injection, anomaly rules | ✅ done |
| M3 | First agent (terminal) with tool calling | ✅ done |
| M4 | Full product: FastAPI agent, React chat UI, one-command compose | ✅ done |
| M5 | CI, integration tests, README polish, measured numbers | — |

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

Besides the UI, the agent is also available as:

```bash
# HTTP API
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

## Testing

```bash
cd platform && ./mvnw verify   # unit tests + spotless formatting check
```

## Future work / non-goals

By design (see the project spec): no auth, no multi-tenancy, no Kubernetes, no Kafka, no real
hardware, no ML, no RAG, single LLM provider only. Production hardening such as idempotent
ingestion and dead-letter handling for bad messages is documented as future work.
