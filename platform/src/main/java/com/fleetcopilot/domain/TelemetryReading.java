package com.fleetcopilot.domain;

import java.time.Instant;

/**
 * One validated telemetry message as published by a device. Numeric fields are nullable because a
 * degraded device may legitimately omit them; identity, time and status are always required.
 */
public record TelemetryReading(
    String deviceId,
    Instant ts,
    Double lat,
    Double lon,
    Double batteryPct,
    Double temperatureC,
    DeviceStatus status,
    String errorCode,
    String firmware) {}
