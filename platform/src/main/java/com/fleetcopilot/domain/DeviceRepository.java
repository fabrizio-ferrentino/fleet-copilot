package com.fleetcopilot.domain;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Maintains the per-device snapshot (last seen, last known state). */
@Repository
public class DeviceRepository {

  // The WHERE clause keeps the snapshot monotonic: a late or replayed
  // message can never overwrite newer state with older data.
  private static final String UPSERT =
      """
      INSERT INTO devices (id, first_seen, last_seen, last_status, last_battery_pct, last_lat, last_lon, firmware)
      VALUES (?, ?, ?, ?, ?, ?, ?, ?)
      ON CONFLICT (id) DO UPDATE SET
        last_seen        = EXCLUDED.last_seen,
        last_status      = EXCLUDED.last_status,
        last_battery_pct = EXCLUDED.last_battery_pct,
        last_lat         = EXCLUDED.last_lat,
        last_lon         = EXCLUDED.last_lon,
        firmware         = EXCLUDED.firmware
      WHERE devices.last_seen <= EXCLUDED.last_seen
      """;

  private final JdbcTemplate jdbc;

  public DeviceRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  public void upsert(TelemetryReading r) {
    OffsetDateTime seen = r.ts().atOffset(ZoneOffset.UTC);
    jdbc.update(
        UPSERT,
        r.deviceId(),
        seen,
        seen,
        r.status().name(),
        r.batteryPct(),
        r.lat(),
        r.lon(),
        r.firmware());
  }
}
