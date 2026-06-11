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

### Definitions

- A device is **offline** when `now() - last_seen > 10 minutes` (threshold configurable from M2
  so tests can shorten it).
- All timestamps are UTC, ISO-8601 (`Z` suffix on the wire, `timestamptz` in the DB).

### Measured (informal, M1)

Local run, 300 devices, 5 s interval: sustained ~60 msg/s ingestion; telemetry grew
1,985 → 3,507 rows in a ~25 s window while 4 malformed messages were rejected and logged.
The formal measurement methodology lands in M5.
