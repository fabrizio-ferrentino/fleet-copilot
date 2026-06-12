package com.fleetcopilot.domain;

import java.time.Instant;

/** One row of the devices snapshot table. */
public record DeviceRow(
    String id,
    Instant firstSeen,
    Instant lastSeen,
    String lastStatus,
    Double lastBatteryPct,
    Double lastLat,
    Double lastLon,
    String firmware) {}
