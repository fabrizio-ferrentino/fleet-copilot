package com.fleetcopilot.domain;

import java.time.ZoneOffset;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Append-only access to the telemetry hypertable. */
@Repository
public class TelemetryRepository {

  private static final String INSERT =
      """
      INSERT INTO telemetry (device_id, ts, lat, lon, battery_pct, temperature_c, status, error_code)
      VALUES (?, ?, ?, ?, ?, ?, ?, ?)
      """;

  private final JdbcTemplate jdbc;

  public TelemetryRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  public void insert(TelemetryReading r) {
    jdbc.update(
        INSERT,
        r.deviceId(),
        r.ts().atOffset(ZoneOffset.UTC),
        r.lat(),
        r.lon(),
        r.batteryPct(),
        r.temperatureC(),
        r.status().name(),
        r.errorCode());
  }
}
