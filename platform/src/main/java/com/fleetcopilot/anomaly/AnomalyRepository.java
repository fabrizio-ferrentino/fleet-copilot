package com.fleetcopilot.anomaly;

import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Data access for anomaly detection. Queries return per-device aggregates shaped for the rules;
 * thresholds live in {@link AnomalyService} where they are unit-testable. The one exception is the
 * GPS speed threshold, applied in SQL because consecutive-point pairs are far too many to pull into
 * memory.
 */
@Repository
public class AnomalyRepository {

  /** Battery level at the start and end of the inspected window, per device. */
  public record BatteryWindow(String deviceId, double firstPct, double lastPct) {}

  /** Worst implied-speed jump of a device within the window. */
  public record GpsJump(
      String deviceId,
      Instant ts,
      double fromLat,
      double fromLon,
      double toLat,
      double toLon,
      double speedKmh) {}

  /** Hottest reading of a device within the window. */
  public record TemperaturePeak(String deviceId, Instant ts, double temperatureC) {}

  private final JdbcTemplate jdbc;

  public AnomalyRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  /** Uses TimescaleDB first()/last() to get battery at the window edges in one scan. */
  public List<BatteryWindow> batteryWindows(Instant since) {
    return jdbc.query(
        """
        SELECT device_id,
               first(battery_pct, ts) AS first_pct,
               last(battery_pct, ts)  AS last_pct
        FROM telemetry
        WHERE ts > ? AND battery_pct IS NOT NULL
        GROUP BY device_id
        """,
        (rs, n) ->
            new BatteryWindow(
                rs.getString("device_id"), rs.getDouble("first_pct"), rs.getDouble("last_pct")),
        since.atOffset(ZoneOffset.UTC));
  }

  /**
   * Implied speed between consecutive points via LAG; distance uses an equirectangular
   * approximation (fine at city scale, and anomalous jumps exceed the threshold by far).
   */
  public List<GpsJump> gpsJumps(Instant since, double minSpeedKmh) {
    return jdbc.query(
        """
        WITH pts AS (
            SELECT device_id, ts, lat, lon,
                   LAG(ts)  OVER w AS prev_ts,
                   LAG(lat) OVER w AS prev_lat,
                   LAG(lon) OVER w AS prev_lon
            FROM telemetry
            WHERE ts > ? AND lat IS NOT NULL AND lon IS NOT NULL
            WINDOW w AS (PARTITION BY device_id ORDER BY ts)
        ),
        jumps AS (
            SELECT device_id, ts, lat, lon, prev_lat, prev_lon,
                   111.32 * sqrt( power(lat - prev_lat, 2)
                                + power((lon - prev_lon) * cos(radians(lat)), 2) )
                     / NULLIF(EXTRACT(EPOCH FROM (ts - prev_ts)) / 3600.0, 0) AS speed_kmh
            FROM pts
            WHERE prev_ts IS NOT NULL AND ts > prev_ts
        )
        SELECT DISTINCT ON (device_id)
               device_id, ts, lat, lon, prev_lat, prev_lon, speed_kmh
        FROM jumps
        WHERE speed_kmh > ?
        ORDER BY device_id, speed_kmh DESC
        """,
        (rs, n) ->
            new GpsJump(
                rs.getString("device_id"),
                rs.getTimestamp("ts").toInstant(),
                rs.getDouble("prev_lat"),
                rs.getDouble("prev_lon"),
                rs.getDouble("lat"),
                rs.getDouble("lon"),
                rs.getDouble("speed_kmh")),
        since.atOffset(ZoneOffset.UTC),
        minSpeedKmh);
  }

  public List<TemperaturePeak> temperaturePeaks(Instant since) {
    return jdbc.query(
        """
        SELECT DISTINCT ON (device_id) device_id, ts, temperature_c
        FROM telemetry
        WHERE ts > ? AND temperature_c IS NOT NULL
        ORDER BY device_id, temperature_c DESC
        """,
        (rs, n) ->
            new TemperaturePeak(
                rs.getString("device_id"),
                rs.getTimestamp("ts").toInstant(),
                rs.getDouble("temperature_c")),
        since.atOffset(ZoneOffset.UTC));
  }
}
