package com.fleetcopilot.ingestion;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "fleet.mqtt")
public record MqttProperties(String uri, String clientId, String topic) {}
