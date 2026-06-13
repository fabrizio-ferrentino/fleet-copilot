package com.fleetcopilot.domain;

import java.util.Locale;

/** Filter values accepted by GET /api/devices?status=… */
public enum DeviceFilter {
  ONLINE,
  OFFLINE,
  OK,
  WARNING,
  ERROR;

  public static DeviceFilter parse(String raw) {
    try {
      return valueOf(raw.trim().toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException e) {
      throw new IllegalArgumentException(
          "unknown status filter '"
              + raw
              + "', expected one of online, offline, ok, warning, error");
    }
  }
}
