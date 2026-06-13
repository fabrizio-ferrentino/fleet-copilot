package com.fleetcopilot.domain;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** A device is offline when it has not been seen for longer than {@code offlineThreshold}. */
@ConfigurationProperties(prefix = "fleet")
public record FleetProperties(Duration offlineThreshold) {}
