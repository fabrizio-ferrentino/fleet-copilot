package com.fleetcopilot.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.fleetcopilot.anomaly.AnomalyRule;
import com.fleetcopilot.anomaly.AnomalyService;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
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

  private void insertBattery(String deviceId, Instant ts, double batteryPct) {
    jdbc.update(
        "INSERT INTO telemetry (device_id, ts, battery_pct, status) VALUES (?, ?, ?, 'OK')",
        deviceId,
        ts.atOffset(ZoneOffset.UTC),
        batteryPct);
  }

  private boolean hasBatteryDrop(String deviceId) {
    return anomalyService.detect(Duration.ofHours(1)).stream()
        .anyMatch(f -> f.deviceId().equals(deviceId) && f.rule() == AnomalyRule.BATTERY_DROP);
  }

  @Test
  void batteryDropAcrossADataGapIsNotFlagged() {
    Instant now = Instant.now();
    // One high reading before a restart, then a steady low run after it (battery re-initialised).
    insertBattery("dev-gap", now.minus(Duration.ofMinutes(50)), 95.0);
    for (int i = 6; i >= 0; i--) {
      insertBattery("dev-gap", now.minus(Duration.ofSeconds(i * 30L)), 60.0);
    }

    // Naive first-vs-last would be 95 -> 60 (a 35-point "drop"); the gap must suppress it.
    assertThat(hasBatteryDrop("dev-gap")).isFalse();
  }

  @Test
  void sustainedBatteryDropWithinContinuousDataIsFlagged() {
    Instant now = Instant.now();
    // Continuous decline 95% -> 60% over ~50 min, readings every 50s (no gap above the threshold).
    double battery = 95.0;
    for (int i = 60; i >= 0; i--) {
      insertBattery("dev-decline", now.minus(Duration.ofSeconds(i * 50L)), battery);
      battery -= 35.0 / 60.0;
    }

    assertThat(hasBatteryDrop("dev-decline")).isTrue();
  }

  private void insertPosition(String deviceId, Instant ts, double lat, double lon) {
    jdbc.update(
        "INSERT INTO telemetry (device_id, ts, lat, lon, status) VALUES (?, ?, ?, ?, 'OK')",
        deviceId,
        ts.atOffset(ZoneOffset.UTC),
        lat,
        lon);
  }

  private boolean hasGpsJump(String deviceId) {
    return anomalyService.detect(Duration.ofHours(1)).stream()
        .anyMatch(f -> f.deviceId().equals(deviceId) && f.rule() == AnomalyRule.GPS_JUMP);
  }

  @Test
  void gpsJumpAcrossADataGapIsNotFlagged() {
    Instant now = Instant.now();
    // ~70 km apart but 90 s apart (a restart re-spawn). Naive speed ~2800 km/h; the gap must drop it.
    insertPosition("dev-gps-gap", now.minus(Duration.ofSeconds(90)), 40.78, 14.59);
    insertPosition("dev-gps-gap", now, 41.30, 15.10);

    assertThat(hasGpsJump("dev-gps-gap")).isFalse();
  }

  @Test
  void fastGpsJumpWithinContinuousDataIsFlagged() {
    Instant now = Instant.now();
    // Same ~70 km jump but only 5 s apart -> a genuine, physically impossible drift.
    insertPosition("dev-gps-fast", now.minus(Duration.ofSeconds(5)), 40.78, 14.59);
    insertPosition("dev-gps-fast", now, 41.30, 15.10);

    assertThat(hasGpsJump("dev-gps-fast")).isTrue();
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
