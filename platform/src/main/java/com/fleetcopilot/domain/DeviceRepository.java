package com.fleetcopilot.domain;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/** Maintains and reads the per-device snapshot (last seen, last known state). */
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

  private static final String SELECT =
      "SELECT id, first_seen, last_seen, last_status, last_battery_pct, last_lat, last_lon,"
          + " firmware FROM devices";

  private static final RowMapper<DeviceRow> ROW_MAPPER =
      (rs, rowNum) ->
          new DeviceRow(
              rs.getString("id"),
              rs.getTimestamp("first_seen").toInstant(),
              rs.getTimestamp("last_seen").toInstant(),
              rs.getString("last_status"),
              nullableDouble(rs, "last_battery_pct"),
              nullableDouble(rs, "last_lat"),
              nullableDouble(rs, "last_lon"),
              rs.getString("firmware"));

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

  public List<DeviceRow> findAll() {
    return jdbc.query(SELECT + " ORDER BY id", ROW_MAPPER);
  }

  public Optional<DeviceRow> findById(String id) {
    return jdbc.query(SELECT + " WHERE id = ?", ROW_MAPPER, id).stream().findFirst();
  }

  private static Double nullableDouble(ResultSet rs, String column) throws SQLException {
    double value = rs.getDouble(column);
    return rs.wasNull() ? null : value;
  }
}
