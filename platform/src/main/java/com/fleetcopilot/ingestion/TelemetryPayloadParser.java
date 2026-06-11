package com.fleetcopilot.ingestion;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fleetcopilot.domain.DeviceStatus;
import com.fleetcopilot.domain.TelemetryReading;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import org.springframework.stereotype.Component;

/**
 * Parses and validates raw MQTT payloads. Validation rejects only what is structurally wrong
 * (malformed JSON, missing identity/time/status, impossible coordinates); weird-but-well-formed
 * data such as a high temperature is ingested, because judging it is the anomaly detector's job,
 * not the parser's.
 */
@Component
public class TelemetryPayloadParser {

  private final ObjectMapper mapper = new ObjectMapper();

  public TelemetryReading parse(String payload) {
    JsonNode root;
    try {
      root = mapper.readTree(payload);
    } catch (JsonProcessingException e) {
      throw new InvalidTelemetryException("malformed JSON: " + e.getOriginalMessage(), e);
    }
    if (root == null || !root.isObject()) {
      throw new InvalidTelemetryException("payload is not a JSON object");
    }
    return new TelemetryReading(
        requireText(root, "deviceId"),
        parseTs(requireText(root, "ts")),
        optionalNumber(root, "lat", -90.0, 90.0),
        optionalNumber(root, "lon", -180.0, 180.0),
        optionalNumber(root, "batteryPct", 0.0, 100.0),
        optionalNumber(root, "temperatureC", -273.15, 1000.0),
        parseStatus(requireText(root, "status")),
        optionalText(root, "errorCode"),
        optionalText(root, "firmware"));
  }

  private static String requireText(JsonNode root, String field) {
    JsonNode node = root.get(field);
    if (node == null || !node.isTextual() || node.asText().isBlank()) {
      throw new InvalidTelemetryException("missing or invalid required field '" + field + "'");
    }
    return node.asText();
  }

  private static String optionalText(JsonNode root, String field) {
    JsonNode node = root.get(field);
    if (node == null || node.isNull()) {
      return null;
    }
    if (!node.isTextual()) {
      throw new InvalidTelemetryException("field '" + field + "' must be a string");
    }
    return node.asText();
  }

  private static Double optionalNumber(JsonNode root, String field, double min, double max) {
    JsonNode node = root.get(field);
    if (node == null || node.isNull()) {
      return null;
    }
    if (!node.isNumber()) {
      throw new InvalidTelemetryException("field '" + field + "' must be a number");
    }
    double value = node.asDouble();
    if (value < min || value > max) {
      throw new InvalidTelemetryException(
          "field '" + field + "' out of range [" + min + ", " + max + "]: " + value);
    }
    return value;
  }

  private static Instant parseTs(String raw) {
    try {
      return OffsetDateTime.parse(raw).toInstant();
    } catch (DateTimeParseException e) {
      throw new InvalidTelemetryException(
          "invalid ts '" + raw + "', expected ISO-8601 with offset", e);
    }
  }

  private static DeviceStatus parseStatus(String raw) {
    try {
      return DeviceStatus.valueOf(raw);
    } catch (IllegalArgumentException e) {
      throw new InvalidTelemetryException("unknown status '" + raw + "'");
    }
  }
}
