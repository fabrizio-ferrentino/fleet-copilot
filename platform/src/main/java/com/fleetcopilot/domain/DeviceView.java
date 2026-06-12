package com.fleetcopilot.domain;

import java.time.Instant;

/** Current state of a device as exposed by the API. */
public record DeviceView(
    String id,
    boolean online,
    Instant firstSeen,
    Instant lastSeen,
    String status,
    Double batteryPct,
    Double lat,
    Double lon,
    String firmware) {

  static DeviceView from(DeviceRow row, boolean online) {
    return new DeviceView(
        row.id(),
        online,
        row.firstSeen(),
        row.lastSeen(),
        row.lastStatus(),
        row.lastBatteryPct(),
        row.lastLat(),
        row.lastLon(),
        row.firmware());
  }
}
