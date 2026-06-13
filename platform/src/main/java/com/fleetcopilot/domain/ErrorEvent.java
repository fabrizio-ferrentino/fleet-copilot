package com.fleetcopilot.domain;

import java.time.Instant;

/** One ERROR telemetry event reported by a device. */
public record ErrorEvent(Instant ts, String errorCode) {}
