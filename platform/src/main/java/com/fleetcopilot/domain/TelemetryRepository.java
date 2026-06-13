package com.fleetcopilot.domain;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/** Append and read access to the telemetry hypertable. */
@Repository
public class TelemetryRepository {

  private static final String INSERT =
      """
      INSERT INTO telemetry (device_id, ts, lat, lon, battery_pct, temperature_c, status, error_code)
      VALUES (?, ?, ?, ?, ?, ?, ?, ?)
      """;

  private static final RowMapper<TelemetryPoint> POINT_MAPPER =
      (rs, rowNum) ->
          new TelemetryPoint(
              rs.getTimestamp("ts").toInstant(),
              nullableDouble(rs, "lat"),
              nullableDouble(rs, "lon"),
              nullableDouble(rs, "battery_pct"),
              nullableDouble(rs, "temperature_c"),
              rs.getString("status"),
              rs.getString("error_code"));

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

  public List<TelemetryPoint> history(String deviceId, Instant from, Instant to, int limit) {
    return jdbc.query(
        """
        SELECT ts, lat, lon, battery_pct, temperature_c, status, error_code
        FROM telemetry
        WHERE device_id = ? AND ts >= ? AND ts <= ?
        ORDER BY ts DESC
        LIMIT ?
        """,
        POINT_MAPPER,
        deviceId,
        from.atOffset(ZoneOffset.UTC),
        to.atOffset(ZoneOffset.UTC),
        limit);
  }

  public List<ErrorEvent> errors(String deviceId, Instant since, int limit) {
    return jdbc.query(
        """
        SELECT ts, error_code FROM telemetry
        WHERE device_id = ? AND status = 'ERROR' AND ts > ?
        ORDER BY ts DESC
        LIMIT ?
        """,
        (rs, rowNum) ->
            new ErrorEvent(rs.getTimestamp("ts").toInstant(), rs.getString("error_code")),
        deviceId,
        since.atOffset(ZoneOffset.UTC),
        limit);
  }

  public int countErrors(String deviceId, Instant since) {
    Integer count =
        jdbc.queryForObject(
            "SELECT count(*) FROM telemetry WHERE device_id = ? AND status = 'ERROR' AND ts > ?",
            Integer.class,
            deviceId,
            since.atOffset(ZoneOffset.UTC));
    return count == null ? 0 : count;
  }

  private static Double nullableDouble(ResultSet rs, String column) throws SQLException {
    double value = rs.getDouble(column);
    return rs.wasNull() ? null : value;
  }
}
