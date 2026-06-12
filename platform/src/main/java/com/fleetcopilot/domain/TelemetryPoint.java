package com.fleetcopilot.domain;

import java.time.Instant;

/** One historical telemetry reading (device id implied by the request path). */
public record TelemetryPoint(
    Instant ts,
    Double lat,
    Double lon,
    Double batteryPct,
    Double temperatureC,
    String status,
    String errorCode) {}
