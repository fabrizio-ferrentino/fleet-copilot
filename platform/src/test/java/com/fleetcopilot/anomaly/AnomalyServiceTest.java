package com.fleetcopilot.anomaly;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fleetcopilot.domain.DeviceRepository;
import com.fleetcopilot.domain.DeviceRow;
import com.fleetcopilot.domain.FleetProperties;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class AnomalyServiceTest {

  private static final Instant NOW = Instant.parse("2026-06-12T10:00:00Z");
  private static final Duration WINDOW = Duration.ofHours(24);

  private final DeviceRepository deviceRepository = mock(DeviceRepository.class);
  private final AnomalyRepository anomalyRepository = mock(AnomalyRepository.class);
  private final AnomalyService service =
      new AnomalyService(
          deviceRepository,
          anomalyRepository,
          new FleetProperties(Duration.ofMinutes(10)),
          Clock.fixed(NOW, ZoneOffset.UTC));

  @BeforeEach
  void emptyByDefault() {
    when(deviceRepository.findAll()).thenReturn(List.of());
    when(anomalyRepository.batteryWindows(any())).thenReturn(List.of());
    when(anomalyRepository.gpsJumps(any(), anyDouble())).thenReturn(List.of());
    when(anomalyRepository.temperaturePeaks(any())).thenReturn(List.of());
  }

  private static DeviceRow device(String id, Instant lastSeen) {
    return new DeviceRow(
        id, NOW.minus(Duration.ofDays(1)), lastSeen, "OK", 80.0, 40.7, 14.6, "1.4.2");
  }

  @Test
  void flagsDevicesSilentBeyondThreshold() {
    when(deviceRepository.findAll())
        .thenReturn(
            List.of(
                device("dev-001", NOW.minus(Duration.ofMinutes(17))),
                device("dev-002", NOW.minus(Duration.ofMinutes(9)))));

    List<AnomalyFinding> findings = service.detect(WINDOW);

    assertThat(findings).hasSize(1);
    AnomalyFinding finding = findings.getFirst();
    assertThat(finding.deviceId()).isEqualTo("dev-001");
    assertThat(finding.rule()).isEqualTo(AnomalyRule.SILENT);
    assertThat(finding.severity()).isEqualTo(Severity.HIGH);
    assertThat(finding.detectedAt()).isEqualTo(NOW);
    assertThat(finding.evidence()).containsEntry("silentForSeconds", 1020L);
  }

  @Test
  void flagsBatteryDropsOverTwentyPoints() {
    when(anomalyRepository.batteryWindows(any()))
        .thenReturn(
            List.of(
                new AnomalyRepository.BatteryWindow("dev-001", 90.0, 69.5), // drop 20.5
                new AnomalyRepository.BatteryWindow("dev-002", 90.0, 71.0), // drop 19.0
                new AnomalyRepository.BatteryWindow("dev-003", 60.0, 85.0))); // charging

    List<AnomalyFinding> findings = service.detect(WINDOW);

    assertThat(findings).hasSize(1);
    AnomalyFinding finding = findings.getFirst();
    assertThat(finding.deviceId()).isEqualTo("dev-001");
    assertThat(finding.rule()).isEqualTo(AnomalyRule.BATTERY_DROP);
    assertThat(finding.severity()).isEqualTo(Severity.MEDIUM);
    assertThat(finding.evidence()).containsEntry("dropPct", 20.5);
  }

  @Test
  void exactTwentyPointDropIsNotFlagged() {
    when(anomalyRepository.batteryWindows(any()))
        .thenReturn(List.of(new AnomalyRepository.BatteryWindow("dev-001", 90.0, 70.0)));

    assertThat(service.detect(WINDOW)).isEmpty();
  }

  @Test
  void mapsGpsJumpsToFindings() {
    when(anomalyRepository.gpsJumps(any(), anyDouble()))
        .thenReturn(
            List.of(
                new AnomalyRepository.GpsJump(
                    "dev-007", NOW.minusSeconds(60), 40.7, 14.5, 40.9, 14.7, 2451.37)));

    List<AnomalyFinding> findings = service.detect(WINDOW);

    assertThat(findings).hasSize(1);
    AnomalyFinding finding = findings.getFirst();
    assertThat(finding.rule()).isEqualTo(AnomalyRule.GPS_JUMP);
    assertThat(finding.severity()).isEqualTo(Severity.MEDIUM);
    assertThat(finding.evidence()).containsEntry("impliedSpeedKmh", 2451.4);
    assertThat(finding.evidence()).containsEntry("toLat", 40.9);
  }

  @Test
  void flagsTemperaturesAboveSeventyDegrees() {
    when(anomalyRepository.temperaturePeaks(any()))
        .thenReturn(
            List.of(
                new AnomalyRepository.TemperaturePeak("dev-001", NOW.minusSeconds(30), 71.2),
                new AnomalyRepository.TemperaturePeak("dev-002", NOW.minusSeconds(30), 70.0)));

    List<AnomalyFinding> findings = service.detect(WINDOW);

    assertThat(findings).hasSize(1);
    AnomalyFinding finding = findings.getFirst();
    assertThat(finding.deviceId()).isEqualTo("dev-001");
    assertThat(finding.rule()).isEqualTo(AnomalyRule.HIGH_TEMPERATURE);
    assertThat(finding.severity()).isEqualTo(Severity.HIGH);
    assertThat(finding.evidence()).containsEntry("temperatureC", 71.2);
  }

  @Test
  void healthyFleetYieldsNoFindings() {
    assertThat(service.detect(WINDOW)).isEmpty();
  }
}
