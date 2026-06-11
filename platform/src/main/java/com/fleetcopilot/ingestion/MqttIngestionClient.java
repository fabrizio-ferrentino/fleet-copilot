package com.fleetcopilot.ingestion;

import jakarta.annotation.PreDestroy;
import java.nio.charset.StandardCharsets;
import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken;
import org.eclipse.paho.client.mqttv3.MqttCallbackExtended;
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
 * Subscribes to the telemetry topic and feeds every message through parse → persist.
 *
 * <p>Resilience rules: the handler swallows (and logs) every per-message failure, because Paho
 * tears the connection down if {@code messageArrived} throws — one bad message must never stop
 * ingestion. Connection drops are healed by Paho's automatic reconnect, and the subscription is
 * (re)made in {@code connectComplete} so it survives broker restarts.
 */
@Component
public class MqttIngestionClient implements MqttCallbackExtended {

  private static final Logger log = LoggerFactory.getLogger(MqttIngestionClient.class);

  private static final int QOS = 1;
  private static final int MAX_CONNECT_ATTEMPTS = 30;
  private static final long CONNECT_RETRY_DELAY_MS = 2000;

  private final MqttProperties properties;
  private final TelemetryPayloadParser parser;
  private final TelemetryIngestionService ingestionService;

  private MqttClient client;

  public MqttIngestionClient(
      MqttProperties properties,
      TelemetryPayloadParser parser,
      TelemetryIngestionService ingestionService) {
    this.properties = properties;
    this.parser = parser;
    this.ingestionService = ingestionService;
  }

  /** Started only once the application is fully up, i.e. after Flyway has migrated the schema. */
  @EventListener(ApplicationReadyEvent.class)
  public void start() {
    try {
      client = new MqttClient(properties.uri(), properties.clientId(), new MemoryPersistence());
      client.setCallback(this);
      MqttConnectOptions options = new MqttConnectOptions();
      options.setAutomaticReconnect(true);
      options.setCleanSession(false);
      connectWithRetry(options);
    } catch (MqttException e) {
      closeQuietly();
      throw new IllegalStateException("Could not connect to MQTT broker " + properties.uri(), e);
    }
  }

  private void connectWithRetry(MqttConnectOptions options) throws MqttException {
    for (int attempt = 1; ; attempt++) {
      try {
        client.connect(options);
        return; // subscription happens in connectComplete()
      } catch (MqttException e) {
        if (attempt >= MAX_CONNECT_ATTEMPTS) {
          throw e;
        }
        log.warn(
            "MQTT broker {} not reachable (attempt {}/{}): {}; retrying in {} ms",
            properties.uri(),
            attempt,
            MAX_CONNECT_ATTEMPTS,
            e.getMessage(),
            CONNECT_RETRY_DELAY_MS);
        try {
          Thread.sleep(CONNECT_RETRY_DELAY_MS);
        } catch (InterruptedException ie) {
          Thread.currentThread().interrupt();
          throw e;
        }
      }
    }
  }

  @Override
  public void connectComplete(boolean reconnect, String serverUri) {
    log.info("{} MQTT broker {}", reconnect ? "Reconnected to" : "Connected to", serverUri);
    try {
      client.subscribe(properties.topic(), QOS);
      log.info("Subscribed to '{}' (qos {})", properties.topic(), QOS);
    } catch (MqttException e) {
      log.error("Failed to subscribe to '{}'", properties.topic(), e);
    }
  }

  @Override
  public void connectionLost(Throwable cause) {
    log.warn(
        "MQTT connection lost ({}); automatic reconnect is active",
        cause == null ? "unknown cause" : cause.getMessage());
  }

  @Override
  public void messageArrived(String topic, MqttMessage message) {
    String payload = new String(message.getPayload(), StandardCharsets.UTF_8);
    try {
      ingestionService.ingest(parser.parse(payload));
    } catch (InvalidTelemetryException e) {
      log.warn("Skipping invalid telemetry on '{}': {}", topic, e.getMessage());
    } catch (Exception e) {
      log.error("Failed to persist telemetry from '{}'; message skipped", topic, e);
    }
  }

  @Override
  public void deliveryComplete(IMqttDeliveryToken token) {
    // subscriber only — nothing is ever published
  }

  @PreDestroy
  public void stop() {
    if (client == null) {
      return;
    }
    try {
      if (client.isConnected()) {
        client.disconnect();
        log.info("Disconnected from MQTT broker");
      }
    } catch (MqttException e) {
      log.warn("Error while disconnecting MQTT client: {}", e.getMessage());
    } finally {
      closeQuietly();
    }
  }

  private void closeQuietly() {
    try {
      client.close();
    } catch (MqttException e) {
      // closing on shutdown — nothing sensible left to do
    }
  }
}
