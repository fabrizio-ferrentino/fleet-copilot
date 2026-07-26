package com.fleetcopilot.anomaly;

import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Data access for anomaly detection. Queries return per-device aggregates shaped for the rules;
 * thresholds live in {@link AnomalyService} where they are unit-testable. Two thresholds live in
 * SQL instead: the GPS speed threshold (consecutive-point pairs are far too many to pull into
 * memory) and the battery {@code GAP_SECONDS} below, which splits a device's readings around
 * restarts/outages so a data discontinuity is not mistaken for a battery drop.
 */
@Repository
public class AnomalyRepository {

  // A jump larger than this between consecutive readings means the device stopped reporting
  // (a restart/outage), not a real time step. The simulator publishes every few seconds, so any
  // multi-minute gap is downtime; on restart a device re-initialises its battery, which would
  // otherwise look like a huge drop when comparing across the gap.
  private static final int GAP_SECONDS = 60;

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

  /**
   * Battery at the edges of each device's most recent <em>uninterrupted</em> run of readings within
   * the window. Splitting on multi-minute gaps means a restart (after which the simulator
   * re-initialises battery) can't masquerade as a drop by comparing a pre-restart reading against a
   * post-restart one. Uses TimescaleDB first()/last() for the edges in a single scan.
   */
  public List<BatteryWindow> batteryWindows(Instant since) {
    return jdbc.query(
        """
        WITH points AS (
            SELECT device_id, battery_pct, ts,
                   CASE
                       WHEN EXTRACT(EPOCH FROM (ts - lag(ts) OVER w)) > ? THEN 1
                       ELSE 0
                   END AS is_break
            FROM telemetry
            WHERE ts > ? AND battery_pct IS NOT NULL
            WINDOW w AS (PARTITION BY device_id ORDER BY ts)
        ),
        segments AS (
            SELECT device_id, battery_pct, ts,
                   sum(is_break) OVER (PARTITION BY device_id ORDER BY ts) AS segment
            FROM points
        ),
        ranked AS (
            SELECT device_id, battery_pct, ts, segment,
                   max(segment) OVER (PARTITION BY device_id) AS last_segment
            FROM segments
        )
        SELECT device_id,
               first(battery_pct, ts) AS first_pct,
               last(battery_pct, ts)  AS last_pct
        FROM ranked
        WHERE segment = last_segment
        GROUP BY device_id
        """,
        (rs, n) ->
            new BatteryWindow(
                rs.getString("device_id"), rs.getDouble("first_pct"), rs.getDouble("last_pct")),
        GAP_SECONDS,
        since.atOffset(ZoneOffset.UTC));
  }

  /**
   * Implied speed between consecutive points via LAG; distance uses an equirectangular
   * approximation (fine at city scale, and anomalous jumps exceed the threshold by far). Pairs more
   * than {@code GAP_SECONDS} apart are dropped: across a restart/outage a device re-initialises its
   * position, and dividing that re-spawn by the gap would imply an impossible speed for everyone.
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
            WHERE prev_ts IS NOT NULL
              AND ts > prev_ts
              AND EXTRACT(EPOCH FROM (ts - prev_ts)) <= ?
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
        GAP_SECONDS,
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
