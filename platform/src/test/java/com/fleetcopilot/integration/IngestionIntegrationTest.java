package com.fleetcopilot.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.fleetcopilot.anomaly.AnomalyRule;
import com.fleetcopilot.anomaly.AnomalyService;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.TimeUnit;
import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.images.builder.Transferable;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * End-to-end ingestion test against real PostgreSQL/TimescaleDB and Mosquitto containers: publish
 * an MQTT message and assert it lands in the database, a malformed message is skipped without
 * crashing ingestion, and a device that stops reporting surfaces as a SILENT anomaly.
 */
@SpringBootTest
@Testcontainers
class IngestionIntegrationTest {

  private static final DockerImageName TIMESCALE =
      DockerImageName.parse("timescale/timescaledb:2.17.2-pg16")
          .asCompatibleSubstituteFor("postgres");

  @Container
  static PostgreSQLContainer<?> postgres =
      new PostgreSQLContainer<>(TIMESCALE)
          .withDatabaseName("fleet")
          .withUsername("fleet")
          .withPassword("fleet");

  @Container
  static GenericContainer<?> mosquitto =
      new GenericContainer<>(DockerImageName.parse("eclipse-mosquitto:2.0"))
          .withExposedPorts(1883)
          .withCopyToContainer(
              Transferable.of("listener 1883\nallow_anonymous true\n"),
              "/mosquitto/config/mosquitto.conf");

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", postgres::getJdbcUrl);
    registry.add("spring.datasource.username", postgres::getUsername);
    registry.add("spring.datasource.password", postgres::getPassword);
    registry.add(
        "fleet.mqtt.uri",
        () -> "tcp://" + mosquitto.getHost() + ":" + mosquitto.getMappedPort(1883));
    // Short threshold so a device that stops reporting becomes "silent" within the test.
    registry.add("fleet.offline-threshold", () -> "2s");
  }

  @Autowired JdbcTemplate jdbc;
  @Autowired AnomalyService anomalyService;

  private MqttClient publisher() throws Exception {
    String uri = "tcp://" + mosquitto.getHost() + ":" + mosquitto.getMappedPort(1883);
    MqttClient client = new MqttClient(uri, "itest-publisher", new MemoryPersistence());
    client.connect();
    return client;
  }

  private void publish(MqttClient client, String deviceId, String json) throws Exception {
    client.publish("fleet/" + deviceId + "/telemetry", new MqttMessage(json.getBytes()));
  }

  private static String reading(String deviceId, String status) {
    return """
        {"deviceId":"%s","ts":"%s","lat":40.78,"lon":14.59,"batteryPct":80.0,
         "temperatureC":40.0,"status":"%s","errorCode":null,"firmware":"1.4.2"}
        """
        .formatted(deviceId, Instant.now(), status);
  }

  private Integer telemetryCount(String deviceId) {
    return jdbc.queryForObject(
        "SELECT count(*) FROM telemetry WHERE device_id = ?", Integer.class, deviceId);
  }

  @Test
  void validMessageIsPersistedToTelemetryAndDeviceSnapshot() throws Exception {
    MqttClient client = publisher();
    try {
      publish(client, "dev-itest", reading("dev-itest", "OK"));

      await()
          .atMost(10, TimeUnit.SECONDS)
          .untilAsserted(() -> assertThat(telemetryCount("dev-itest")).isPositive());

      Integer devices =
          jdbc.queryForObject(
              "SELECT count(*) FROM devices WHERE id = ?", Integer.class, "dev-itest");
      assertThat(devices).isEqualTo(1);
    } finally {
      client.disconnect();
    }
  }

  @Test
  void malformedMessageIsSkippedAndIngestionKeepsWorking() throws Exception {
    MqttClient client = publisher();
    try {
      publish(client, "dev-bad", "{this is not json");
      publish(client, "dev-good", reading("dev-good", "OK"));

      // The good message still lands, proving the bad one did not break the pipeline.
      await()
          .atMost(10, TimeUnit.SECONDS)
          .untilAsserted(() -> assertThat(telemetryCount("dev-good")).isPositive());
      assertThat(telemetryCount("dev-bad")).isZero();
    } finally {
      client.disconnect();
    }
  }

  @Test
  void silentDeviceSurfacesAsAnomaly() throws Exception {
    MqttClient client = publisher();
    try {
      publish(client, "dev-silent", reading("dev-silent", "OK"));
      await()
          .atMost(10, TimeUnit.SECONDS)
          .untilAsserted(() -> assertThat(telemetryCount("dev-silent")).isPositive());

      // Stop publishing; after the 2s threshold the device must be reported as SILENT.
      await()
          .atMost(10, TimeUnit.SECONDS)
          .untilAsserted(
              () -> {
                boolean silent =
                    anomalyService.detect(Duration.ofHours(1)).stream()
                        .anyMatch(
                            f ->
                                f.deviceId().equals("dev-silent")
                                    && f.rule() == AnomalyRule.SILENT);
                assertThat(silent).isTrue();
              });
    } finally {
      client.disconnect();
    }
  }
}
