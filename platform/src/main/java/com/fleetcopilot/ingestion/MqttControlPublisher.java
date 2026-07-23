package com.fleetcopilot.ingestion;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import java.util.Map;
import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Publishes fault-injection commands to the broker's control topic, which the simulator listens on,
 * so the UI (and tests) can break the fleet without a shell. Publish-only — the ingestion path
 * stays a pure subscriber; this opens its own connection with a distinct client id.
 */
@Component
public class MqttControlPublisher {

  private static final Logger log = LoggerFactory.getLogger(MqttControlPublisher.class);

  private static final int QOS = 1;
  private static final int MAX_CONNECT_ATTEMPTS = 30;
  private static final long CONNECT_RETRY_DELAY_MS = 2000;

  private final MqttProperties properties;
  private final ObjectMapper objectMapper;

  private MqttClient client;

  public MqttControlPublisher(MqttProperties properties, ObjectMapper objectMapper) {
    this.properties = properties;
    this.objectMapper = objectMapper;
  }

  @EventListener(ApplicationReadyEvent.class)
  public void start() {
    try {
      client =
          new MqttClient(
              properties.uri(), properties.clientId() + "-control", new MemoryPersistence());
      MqttConnectOptions options = new MqttConnectOptions();
      options.setAutomaticReconnect(true);
      options.setCleanSession(true);
      connectWithRetry(options);
    } catch (MqttException e) {
      // Control is non-critical: never fail startup; automatic reconnect heals later drops.
      log.warn("Control publisher could not connect to {}: {}", properties.uri(), e.getMessage());
    }
  }

  private void connectWithRetry(MqttConnectOptions options) throws MqttException {
    for (int attempt = 1; ; attempt++) {
      try {
        client.connect(options);
        log.info(
            "Control publisher connected to {} (topic '{}')",
            properties.uri(),
            properties.controlTopic());
        return;
      } catch (MqttException e) {
        if (attempt >= MAX_CONNECT_ATTEMPTS) {
          throw e;
        }
        try {
          Thread.sleep(CONNECT_RETRY_DELAY_MS);
        } catch (InterruptedException ie) {
          Thread.currentThread().interrupt();
          throw e;
        }
      }
    }
  }

  /** Publishes {@code {"deviceId":..,"fault":..}} to the control topic; throws if it cannot. */
  public void publishFault(String deviceId, String fault) {
    if (client == null || !client.isConnected()) {
      throw new IllegalStateException("control channel is not connected to the MQTT broker");
    }
    try {
      byte[] payload = objectMapper.writeValueAsBytes(Map.of("deviceId", deviceId, "fault", fault));
      MqttMessage message = new MqttMessage(payload);
      message.setQos(QOS);
      client.publish(properties.controlTopic(), message);
    } catch (Exception e) {
      throw new IllegalStateException("failed to publish control message: " + e.getMessage(), e);
    }
  }

  @PreDestroy
  public void stop() {
    if (client == null) {
      return;
    }
    try {
      if (client.isConnected()) {
        client.disconnect();
      }
    } catch (MqttException e) {
      log.warn("Error disconnecting control publisher: {}", e.getMessage());
    } finally {
      try {
        client.close();
      } catch (MqttException ignored) {
        // closing on shutdown — nothing sensible left to do
      }
    }
  }
}
