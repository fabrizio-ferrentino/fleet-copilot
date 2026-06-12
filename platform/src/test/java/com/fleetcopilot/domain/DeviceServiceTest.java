package com.fleetcopilot.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class DeviceServiceTest {

  private static final Instant NOW = Instant.parse("2026-06-12T10:00:00Z");

  private final DeviceRepository repository = mock(DeviceRepository.class);
  private final DeviceService service =
      new DeviceService(
          repository,
          new FleetProperties(Duration.ofMinutes(10)),
          Clock.fixed(NOW, ZoneOffset.UTC));

  private static DeviceRow row(String id, Instant lastSeen, String status) {
    return new DeviceRow(
        id, NOW.minus(Duration.ofDays(1)), lastSeen, status, 80.0, 40.7, 14.6, "1.4.2");
  }

  @Test
  void fleetStatusCountsOnlineOfflineAndStatuses() {
    when(repository.findAll())
        .thenReturn(
            List.of(
                row("dev-001", NOW.minusSeconds(60), "OK"),
                row("dev-002", NOW.minusSeconds(120), "WARNING"),
                // offline: stale status must not be counted as an active error
                row("dev-003", NOW.minus(Duration.ofMinutes(11)), "ERROR")));

    FleetStatus status = service.fleetStatus();

    assertThat(status.total()).isEqualTo(3);
    assertThat(status.online()).isEqualTo(2);
    assertThat(status.offline()).isEqualTo(1);
    assertThat(status.warning()).isEqualTo(1);
    assertThat(status.error()).isZero();
  }

  @Test
  void deviceSeenExactlyAtThresholdIsOffline() {
    when(repository.findAll())
        .thenReturn(List.of(row("dev-001", NOW.minus(Duration.ofMinutes(10)), "OK")));

    assertThat(service.fleetStatus().offline()).isEqualTo(1);
  }

  @Test
  void listFiltersByOnlineState() {
    when(repository.findAll())
        .thenReturn(
            List.of(
                row("dev-001", NOW.minusSeconds(30), "OK"),
                row("dev-002", NOW.minus(Duration.ofMinutes(20)), "OK")));

    List<DeviceView> offline = service.list(DeviceFilter.OFFLINE);

    assertThat(offline).hasSize(1);
    assertThat(offline.getFirst().id()).isEqualTo("dev-002");
    assertThat(offline.getFirst().online()).isFalse();
  }

  @Test
  void listFiltersByLastStatus() {
    when(repository.findAll())
        .thenReturn(
            List.of(
                row("dev-001", NOW.minusSeconds(30), "OK"),
                row("dev-002", NOW.minusSeconds(30), "ERROR")));

    List<DeviceView> errors = service.list(DeviceFilter.ERROR);

    assertThat(errors).hasSize(1);
    assertThat(errors.getFirst().id()).isEqualTo("dev-002");
  }

  @Test
  void getUnknownDeviceThrowsNotFound() {
    when(repository.findById("dev-999")).thenReturn(Optional.empty());

    assertThatThrownBy(() -> service.get("dev-999"))
        .isInstanceOf(NotFoundException.class)
        .hasMessageContaining("dev-999");
  }

  @Test
  void parseRejectsUnknownFilter() {
    assertThatThrownBy(() -> DeviceFilter.parse("broken"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("unknown status filter");
  }
}
