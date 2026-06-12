-- Telemetry schema: a snapshot table for the current state of each device
-- and a TimescaleDB hypertable for the append-only telemetry history.

CREATE EXTENSION IF NOT EXISTS timescaledb;

CREATE TABLE devices (
    id               TEXT PRIMARY KEY,
    first_seen       TIMESTAMPTZ NOT NULL,
    last_seen        TIMESTAMPTZ NOT NULL,
    last_status      TEXT        NOT NULL,
    last_battery_pct DOUBLE PRECISION,
    last_lat         DOUBLE PRECISION,
    last_lon         DOUBLE PRECISION,
    firmware         TEXT
);

CREATE TABLE telemetry (
    device_id     TEXT        NOT NULL,
    ts            TIMESTAMPTZ NOT NULL,
    lat           DOUBLE PRECISION,
    lon           DOUBLE PRECISION,
    battery_pct   DOUBLE PRECISION,
    temperature_c DOUBLE PRECISION,
    status        TEXT        NOT NULL,
    error_code    TEXT
);

-- Partition the history by time; all queries the agent will run are
-- "device X over time window Y", which hypertable chunks serve well.
SELECT create_hypertable('telemetry', 'ts');

CREATE INDEX idx_telemetry_device_ts ON telemetry (device_id, ts DESC);
