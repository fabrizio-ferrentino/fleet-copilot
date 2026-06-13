package com.fleetcopilot.domain;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import org.springframework.stereotype.Service;

/**
 * Read model over the devices snapshot. The snapshot is one row per device (300 at default scale),
 * so online/offline and status filtering happen in memory on top of a trivial SELECT; at a much
 * larger fleet size these filters would move into SQL.
 */
@Service
public class DeviceService {

  private final DeviceRepository deviceRepository;
  private final FleetProperties properties;
  private final Clock clock;

  public DeviceService(DeviceRepository deviceRepository, FleetProperties properties, Clock clock) {
    this.deviceRepository = deviceRepository;
    this.properties = properties;
    this.clock = clock;
  }

  public FleetStatus fleetStatus() {
    List<DeviceRow> rows = deviceRepository.findAll();
    Instant cutoff = offlineCutoff();
    int online = 0;
    int warning = 0;
    int error = 0;
    for (DeviceRow row : rows) {
      if (isOnline(row, cutoff)) {
        online++;
        if ("WARNING".equals(row.lastStatus())) {
          warning++;
        } else if ("ERROR".equals(row.lastStatus())) {
          error++;
        }
      }
    }
    return new FleetStatus(rows.size(), online, rows.size() - online, warning, error);
  }

  /** Lists devices, optionally filtered; a null filter returns the whole fleet. */
  public List<DeviceView> list(DeviceFilter filter) {
    Instant cutoff = offlineCutoff();
    return deviceRepository.findAll().stream()
        .filter(row -> matches(row, filter, cutoff))
        .map(row -> DeviceView.from(row, isOnline(row, cutoff)))
        .toList();
  }

  public DeviceView get(String id) {
    return deviceRepository
        .findById(id)
        .map(row -> DeviceView.from(row, isOnline(row, offlineCutoff())))
        .orElseThrow(() -> new NotFoundException("device '" + id + "' not found"));
  }

  public Instant now() {
    return clock.instant();
  }

  private boolean matches(DeviceRow row, DeviceFilter filter, Instant cutoff) {
    if (filter == null) {
      return true;
    }
    return switch (filter) {
      case ONLINE -> isOnline(row, cutoff);
      case OFFLINE -> !isOnline(row, cutoff);
      case OK, WARNING, ERROR -> filter.name().equals(row.lastStatus());
    };
  }

  private boolean isOnline(DeviceRow row, Instant cutoff) {
    return row.lastSeen().isAfter(cutoff);
  }

  private Instant offlineCutoff() {
    return clock.instant().minus(properties.offlineThreshold());
  }
}
