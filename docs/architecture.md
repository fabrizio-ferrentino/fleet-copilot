# Architecture notes

Extended notes per milestone. The diagram source of truth lives in the README (ASCII); this file
records the *why* behind each decision so they can be defended later.

## Milestone 1 — data flows

```
simulator (Python, paho-mqtt)
   │  publish fleet/{deviceId}/telemetry, qos 1, every INTERVAL s per device
   ▼
mosquitto 2.0 (Docker, anonymous, no persistence)
   │  subscribe fleet/+/telemetry, qos 1
   ▼
platform (Spring Boot 3.5, Java 21, no web layer yet)
   │  parse → validate → persist (one transaction per message)
   ▼
postgres 16 + timescaledb 2.17 (Docker)
   ├─ devices    — one row per device: snapshot of last known state
   └─ telemetry  — hypertable on ts: append-only history
```

### Decisions

**Two tables, snapshot + history.** Fleet-wide questions ("how many devices are offline?") need
only the 300-row `devices` snapshot, kept fresh by an `ON CONFLICT` upsert per message. Historical
questions ("what did dev-042 report in the last hour?") hit the `telemetry` hypertable. Without
the snapshot, every fleet-status query would scan recent history for all devices.

**TimescaleDB hypertable for telemetry.** Telemetry is append-only and every query is
time-bounded. A hypertable partitions rows into time chunks transparently, so "last 24 h of
dev-042" touches only recent chunks instead of one ever-growing table/index. At M1 rates
(~60 msg/s ≈ 5.2 M rows/day) a plain table would degrade within days.

**QoS 1 end-to-end, persistent subscriber session.** At-least-once delivery between simulator,
broker and platform: if the platform restarts, the broker re-delivers what it missed. The cost is
possible duplicates (no dedup in M1 — idempotent ingestion is documented future work, and the
device snapshot upsert already refuses to apply out-of-date state).

**Validation rejects structure, not values.** The parser drops malformed JSON, missing
identity/time/status, and physically impossible values (lat 123°), logging the reason and
carrying on — ingestion must survive any input. But weird-yet-well-formed data (temperature
75 °C) is *ingested*, because judging it is the anomaly detector's job (M2), and the agent will
need the evidence in the history.

**Catch-all in the MQTT callback.** Paho drops the connection if `messageArrived` throws, so a
single poison message could otherwise stall the entire fleet feed. Per-message failures are
logged and swallowed; connection drops heal via automatic reconnect + resubscribe in
`connectComplete`.

**Plain JDBC (`JdbcTemplate`) instead of JPA.** Ingestion is two explicit SQL statements
(INSERT + upsert with `ON CONFLICT ... WHERE`), and the upsert plus TimescaleDB-specific SQL
would need native queries under JPA anyway. JdbcTemplate keeps the write path obvious and cheap;
there is no object graph to manage.

**No web starter yet.** The REST API is milestone 2; in M1 the platform runs as a non-web Spring
Boot process kept alive by the (non-daemon) Paho client threads. The MQTT client starts on
`ApplicationReadyEvent`, which guarantees Flyway has migrated the schema before the first message
can arrive.

**Simulator publish scheduling.** One thread and a heap of (next-due, device) entries instead of
300 threads; initial publishes are staggered across one interval so the broker sees a smooth
~60 msg/s stream rather than a 300-message burst every 5 s.

**Postgres published on host port 5433.** Inside the compose network the database listens on the
standard 5432, but it is published to the host as 5433: many developer machines already run a
local PostgreSQL on 5432, and colliding with it produces confusing auth failures against the
wrong server (two listeners can even coexist silently on Windows). The platform's `DB_PORT`
default follows suit; everything inside Docker keeps using 5432.

### Definitions

- A device is **offline** when `now() - last_seen > 10 minutes` (threshold configurable from M2
  so tests can shorten it).
- All timestamps are UTC, ISO-8601 (`Z` suffix on the wire, `timestamptz` in the DB).

## Milestone 2 — queryable and breakable

### Decisions

**Thin REST layer over the snapshot.** Fleet status and device filtering run in memory on the
300-row `devices` snapshot (one trivial SELECT); at a much larger fleet these filters would move
into SQL. Warning/error totals only count *online* devices: the last status of an offline device
is stale information, and the offline count already covers it.

**One compact window format.** Time windows arrive as `30s | 15m | 24h | 7d` (max 7d), parsed in
one place (`WindowParser`). The agent gets a single convention to learn instead of per-endpoint
date math.

**Errors endpoint returns count + latest.** An `error_burst` device emits an ERROR every
interval, thousands per day; returning the full list would flood the agent's context. The
response carries the total count in the window plus the 50 most recent events.

**Anomaly thresholds live in Java, data shaping in SQL.** Each rule's SQL returns per-device
aggregates (battery at window edges via TimescaleDB `first()`/`last()`, hottest reading, worst
GPS jump) and the threshold comparison happens in plain Java where it is unit-testable with a
fixed clock. The one exception is the GPS speed cut-off, applied in SQL because consecutive-point
pairs are far too many to pull into memory — a deliberate, documented trade-off.

**Battery rule is direction-aware.** Drop = battery at window start − battery at window end, so
a device that *charged* 25 points is not flagged. A V-shape (fast drop then recharge) inside the
hour can slip through; accepted as part of "deliberately simple".

**GPS distance is an equirectangular approximation.** Exact haversine is unnecessary: normal
movement implies tens of km/h, the threshold is 200, and faults imply thousands — the
approximation error is irrelevant at city scale.

**One finding per device per rule.** A drifting device produces a jump on every tick; reporting
only the worst keeps `/api/anomalies` compact enough for the agent to reason over.

**Faults are simulator-side state.** A fault changes how the device behaves from the next tick
(`silent` skips publishing, `battery_drain` multiplies the drain rate and blocks charging,
`gps_drift` jumps kilometres per tick, `error_burst` forces status ERROR with a stable error
code). `clear` was added beyond the spec so demos can also show recovery. Control messages are
validated and logged; invalid ones are ignored, never fatal.

## Milestone 3 — first intelligence

### Decisions

**The agent calls REST tools, never the database.** The API is the same operator-grade contract
a human would use: stable, validated, compact. The platform keeps owning query shaping and
optimization, the agent gets no SQL surface to misuse, and every step of the investigation is a
meaningful HTTP call that can be shown to the user as a trace.

**Provider behind a one-class interface.** `loop.py` only sees neutral types (`ToolSpec`,
`LlmTurn`, `Conversation`); Gemini lives in one class in `llm.py`. Swapping providers means
writing one new class — but per the non-goals only Gemini is implemented.

**Max 6 tool calls per question.** Bounds cost and latency, and forces the model to investigate
decisively instead of wandering. The budget is enforced in the loop, not trusted to the model:
past 6, requested calls receive an `{"error": "budget exhausted"}` payload, which pushes the
model to conclude from what it already has. A separate round cap protects against a model that
never stops calling tools.

**Failures are data, not exceptions.** A dead platform or a 4xx becomes `{"error": ...}` in the
tool result, so the model can say "I could not check X" — exactly what the system prompt demands
when data is insufficient ("answer only from tool data; say so if it is not enough").

**The trace is a first-class output.** Every call is recorded as `{tool, args, resultSummary}`;
the CLI prints it under the answer and the M4 chat UI will render it as the "how I investigated"
panel. Summaries are deliberately compact (counts + first ids) to stay readable.

## Milestone 4 — full product

### Decisions

**Compose topology.** Healthchecks live on the stateful infrastructure (postgres, mosquitto) and
the platform waits for both to be healthy, per spec. The agent and UI only need start ordering:
the agent's tool layer already treats an unreachable platform as data (`{"error": ...}`), and the
UI shows a clear error bubble if the agent is down. Inside the network services talk via service
names (`postgres:5432`, `mosquitto:1883`, `platform:8080`); only the browser-facing ports are
published.

**FastAPI app factory.** The agent runs as `uvicorn agent.main:create_app --factory`: the Gemini
provider is built at startup, so a missing `GEMINI_API_KEY` fails fast with one clear log line
instead of failing on the first question — and tests inject a stub loop through the same factory.

**Wire contract straight from the spec.** `POST /ask` returns `{answer, toolTrace:[{tool, args,
resultSummary}]}` (camelCase on the wire); the UI types mirror it 1:1. CORS is wide open because
the project is local-only by design (non-goal: no auth).

**UI as static files behind nginx.** Multi-stage build: node compiles, nginx serves ~60 kB of
assets. The agent URL is baked at build time (`VITE_AGENT_URL`, default `http://localhost:8000`)
— correct for compose because the *browser*, not the container, calls the agent through the
published port.

**Token hygiene on history.** `get_device_history` defaults to 20 readings (hard cap 200) even
though the API allows 1000: recent points answer diagnostic questions, and the agent's context
stays small.

### Measured (informal, M1)

Local run, 300 devices, 5 s interval: sustained ~60 msg/s ingestion; telemetry grew
1,985 → 3,507 rows in a ~25 s window while 4 malformed messages were rejected and logged.
The formal measurement methodology lands in M5.
