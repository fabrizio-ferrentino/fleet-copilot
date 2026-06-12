package com.fleetcopilot.ingestion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fleetcopilot.domain.DeviceStatus;
import com.fleetcopilot.domain.TelemetryReading;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class TelemetryPayloadParserTest {

  private final TelemetryPayloadParser parser = new TelemetryPayloadParser();

  private static final String VALID =
      """
      {"deviceId":"dev-042","ts":"2026-06-13T10:15:00Z","lat":40.78,"lon":14.59,
       "batteryPct":87.5,"temperatureC":41.2,"status":"OK","errorCode":null,"firmware":"1.4.2"}
      """;

  @Test
  void parsesValidPayload() {
    TelemetryReading r = parser.parse(VALID);

    assertThat(r.deviceId()).isEqualTo("dev-042");
    assertThat(r.ts()).isEqualTo(Instant.parse("2026-06-13T10:15:00Z"));
    assertThat(r.lat()).isEqualTo(40.78);
    assertThat(r.lon()).isEqualTo(14.59);
    assertThat(r.batteryPct()).isEqualTo(87.5);
    assertThat(r.temperatureC()).isEqualTo(41.2);
    assertThat(r.status()).isEqualTo(DeviceStatus.OK);
    assertThat(r.errorCode()).isNull();
    assertThat(r.firmware()).isEqualTo("1.4.2");
  }

  @Test
  void parsesErrorStatusWithErrorCode() {
    TelemetryReading r =
        parser.parse(
            """
            {"deviceId":"dev-001","ts":"2026-06-13T10:15:00Z","status":"ERROR","errorCode":"E-PWR-03"}
            """);

    assertThat(r.status()).isEqualTo(DeviceStatus.ERROR);
    assertThat(r.errorCode()).isEqualTo("E-PWR-03");
  }

  @Test
  void acceptsTimestampWithExplicitOffset() {
    TelemetryReading r =
        parser.parse(
            """
            {"deviceId":"dev-001","ts":"2026-06-13T12:15:00+02:00","status":"OK"}
            """);

    assertThat(r.ts()).isEqualTo(Instant.parse("2026-06-13T10:15:00Z"));
  }

  @Test
  void allowsMissingOptionalFields() {
    TelemetryReading r =
        parser.parse(
            """
            {"deviceId":"dev-001","ts":"2026-06-13T10:15:00Z","status":"OK"}
            """);

    assertThat(r.lat()).isNull();
    assertThat(r.lon()).isNull();
    assertThat(r.batteryPct()).isNull();
    assertThat(r.temperatureC()).isNull();
    assertThat(r.errorCode()).isNull();
    assertThat(r.firmware()).isNull();
  }

  @Test
  void rejectsMalformedJson() {
    assertThatThrownBy(() -> parser.parse("{this is not json"))
        .isInstanceOf(InvalidTelemetryException.class)
        .hasMessageContaining("malformed JSON");
  }

  @Test
  void rejectsNonObjectPayload() {
    assertThatThrownBy(() -> parser.parse("42"))
        .isInstanceOf(InvalidTelemetryException.class)
        .hasMessageContaining("not a JSON object");
  }

  @Test
  void rejectsMissingDeviceId() {
    assertThatThrownBy(
            () ->
                parser.parse(
                    """
            {"ts":"2026-06-13T10:15:00Z","status":"OK"}
            """))
        .isInstanceOf(InvalidTelemetryException.class)
        .hasMessageContaining("deviceId");
  }

  @Test
  void rejectsBlankDeviceId() {
    assertThatThrownBy(
            () ->
                parser.parse(
                    """
                    {"deviceId":"  ","ts":"2026-06-13T10:15:00Z","status":"OK"}
                    """))
        .isInstanceOf(InvalidTelemetryException.class)
        .hasMessageContaining("deviceId");
  }

  @Test
  void rejectsInvalidTimestamp() {
    assertThatThrownBy(
            () ->
                parser.parse(
                    """
                    {"deviceId":"dev-001","ts":"yesterday","status":"OK"}
                    """))
        .isInstanceOf(InvalidTelemetryException.class)
        .hasMessageContaining("ts");
  }

  @Test
  void rejectsUnknownStatus() {
    assertThatThrownBy(
            () ->
                parser.parse(
                    """
                    {"deviceId":"dev-001","ts":"2026-06-13T10:15:00Z","status":"BROKEN"}
                    """))
        .isInstanceOf(InvalidTelemetryException.class)
        .hasMessageContaining("BROKEN");
  }

  @Test
  void rejectsOutOfRangeLatitude() {
    assertThatThrownBy(
            () ->
                parser.parse(
                    """
                    {"deviceId":"dev-001","ts":"2026-06-13T10:15:00Z","status":"OK","lat":123.4}
                    """))
        .isInstanceOf(InvalidTelemetryException.class)
        .hasMessageContaining("lat");
  }

  @Test
  void rejectsNonNumericBattery() {
    assertThatThrownBy(
            () ->
                parser.parse(
                    """
                    {"deviceId":"dev-001","ts":"2026-06-13T10:15:00Z","status":"OK","batteryPct":"full"}
                    """))
        .isInstanceOf(InvalidTelemetryException.class)
        .hasMessageContaining("batteryPct");
  }
}
