package com.fleetcopilot.ingestion;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties(prefix = "fleet.mqtt")
public record MqttProperties(
    String uri,
    String clientId,
    String topic,
    @DefaultValue("fleet/control") String controlTopic) {}
