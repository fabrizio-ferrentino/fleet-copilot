package com.fleetcopilot.domain;

/** Raised when a requested resource does not exist; mapped to HTTP 404. */
public class NotFoundException extends RuntimeException {

  public NotFoundException(String message) {
    super(message);
  }
}
