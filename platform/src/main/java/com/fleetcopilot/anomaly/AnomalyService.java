package com.fleetcopilot.anomaly;

import com.fleetcopilot.domain.DeviceRepository;
import com.fleetcopilot.domain.DeviceRow;
import com.fleetcopilot.domain.FleetProperties;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

/**
 * The four rules, deliberately simple and threshold-based (no ML). The window parameter bounds the
 * lookback of event rules (GPS jumps, temperature); the battery rule is fixed at 1 h by definition,
 * and the silent rule reflects the current state of the fleet.
 */
@Service
public class AnomalyService {

  static final Duration BATTERY_WINDOW = Duration.ofHours(1);
  static final double BATTERY_DROP_PCT = 20.0;
  static final double GPS_SPEED_KMH = 200.0;
  static final double TEMPERATURE_LIMIT_C = 70.0;

  private final DeviceRepository deviceRepository;
  private final AnomalyRepository anomalyRepository;
  private final FleetProperties properties;
  private final Clock clock;

  public AnomalyService(
      DeviceRepository deviceRepository,
      AnomalyRepository anomalyRepository,
      FleetProperties properties,
      Clock clock) {
    this.deviceRepository = deviceRepository;
    this.anomalyRepository = anomalyRepository;
    this.properties = properties;
    this.clock = clock;
  }

  public List<AnomalyFinding> detect(Duration window) {
    Instant now = clock.instant();
    List<AnomalyFinding> findings = new ArrayList<>();
    findings.addAll(silentDevices(now));
    findings.addAll(batteryDrops(now));
    findings.addAll(gpsJumps(now, window));
    findings.addAll(highTemperatures(now, window));
    return findings;
  }

  private List<AnomalyFinding> silentDevices(Instant now) {
    Instant cutoff = now.minus(properties.offlineThreshold());
    List<AnomalyFinding> findings = new ArrayList<>();
    for (DeviceRow device : deviceRepository.findAll()) {
      if (!device.lastSeen().isAfter(cutoff)) {
        long silentSeconds = Duration.between(device.lastSeen(), now).toSeconds();
        findings.add(
            new AnomalyFinding(
                device.id(),
                AnomalyRule.SILENT,
                Severity.HIGH,
                now,
                Map.of("lastSeen", device.lastSeen(), "silentForSeconds", silentSeconds)));
      }
    }
    return findings;
  }

  private List<AnomalyFinding> batteryDrops(Instant now) {
    List<AnomalyFinding> findings = new ArrayList<>();
    for (AnomalyRepository.BatteryWindow w :
        anomalyRepository.batteryWindows(now.minus(BATTERY_WINDOW))) {
      double drop = w.firstPct() - w.lastPct();
      if (drop > BATTERY_DROP_PCT) {
        findings.add(
            new AnomalyFinding(
                w.deviceId(),
                AnomalyRule.BATTERY_DROP,
                Severity.MEDIUM,
                now,
                Map.of(
                    "batteryFromPct", w.firstPct(),
                    "batteryToPct", w.lastPct(),
                    "dropPct", round1(drop),
                    "window", "1h")));
      }
    }
    return findings;
  }

  private List<AnomalyFinding> gpsJumps(Instant now, Duration window) {
    List<AnomalyFinding> findings = new ArrayList<>();
    for (AnomalyRepository.GpsJump jump :
        anomalyRepository.gpsJumps(now.minus(window), GPS_SPEED_KMH)) {
      findings.add(
          new AnomalyFinding(
              jump.deviceId(),
              AnomalyRule.GPS_JUMP,
              Severity.MEDIUM,
              now,
              Map.of(
                  "ts", jump.ts(),
                  "impliedSpeedKmh", round1(jump.speedKmh()),
                  "fromLat", jump.fromLat(),
                  "fromLon", jump.fromLon(),
                  "toLat", jump.toLat(),
                  "toLon", jump.toLon())));
    }
    return findings;
  }

  private List<AnomalyFinding> highTemperatures(Instant now, Duration window) {
    List<AnomalyFinding> findings = new ArrayList<>();
    for (AnomalyRepository.TemperaturePeak peak :
        anomalyRepository.temperaturePeaks(now.minus(window))) {
      if (peak.temperatureC() > TEMPERATURE_LIMIT_C) {
        findings.add(
            new AnomalyFinding(
                peak.deviceId(),
                AnomalyRule.HIGH_TEMPERATURE,
                Severity.HIGH,
                now,
                Map.of("ts", peak.ts(), "temperatureC", peak.temperatureC())));
      }
    }
    return findings;
  }

  private static double round1(double value) {
    return Math.round(value * 10.0) / 10.0;
  }
}
