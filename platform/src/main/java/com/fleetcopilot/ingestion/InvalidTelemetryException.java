package com.fleetcopilot.ingestion;

/** Raised when an incoming MQTT payload cannot be parsed into a valid telemetry reading. */
public class InvalidTelemetryException extends RuntimeException {

  public InvalidTelemetryException(String message) {
    super(message);
  }

  public InvalidTelemetryException(String message, Throwable cause) {
    super(message, cause);
  }
}
