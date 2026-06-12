package com.fleetcopilot.anomaly;

import java.time.Instant;
import java.util.Map;

/** One rule-based finding, with the evidence the agent needs to cite (timestamps, values). */
public record AnomalyFinding(
    String deviceId,
    AnomalyRule rule,
    Severity severity,
    Instant detectedAt,
    Map<String, Object> evidence) {}
