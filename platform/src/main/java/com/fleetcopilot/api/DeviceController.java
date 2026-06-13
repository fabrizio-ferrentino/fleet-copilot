package com.fleetcopilot.api;

import com.fleetcopilot.domain.DeviceFilter;
import com.fleetcopilot.domain.DeviceService;
import com.fleetcopilot.domain.DeviceView;
import com.fleetcopilot.domain.ErrorEvent;
import com.fleetcopilot.domain.TelemetryPoint;
import com.fleetcopilot.domain.TelemetryRepository;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/devices")
public class DeviceController {

  private static final int MAX_LIMIT = 1000;
  private static final int MAX_ERROR_EVENTS = 50;

  private final DeviceService deviceService;
  private final TelemetryRepository telemetryRepository;

  public DeviceController(DeviceService deviceService, TelemetryRepository telemetryRepository) {
    this.deviceService = deviceService;
    this.telemetryRepository = telemetryRepository;
  }

  @GetMapping
  public List<DeviceView> list(@RequestParam(required = false) String status) {
    return deviceService.list(status == null ? null : DeviceFilter.parse(status));
  }

  @GetMapping("/{id}")
  public DeviceView get(@PathVariable String id) {
    return deviceService.get(id);
  }

  @GetMapping("/{id}/telemetry")
  public List<TelemetryPoint> telemetry(
      @PathVariable String id,
      @RequestParam(required = false) String from,
      @RequestParam(required = false) String to,
      @RequestParam(defaultValue = "100") int limit) {
    deviceService.get(id); // 404 for unknown devices
    if (limit < 1 || limit > MAX_LIMIT) {
      throw new IllegalArgumentException("limit must be between 1 and " + MAX_LIMIT);
    }
    Instant fromTs = parseInstant(from, Instant.EPOCH);
    Instant toTs = parseInstant(to, deviceService.now());
    if (toTs.isBefore(fromTs)) {
      throw new IllegalArgumentException("'to' must not be before 'from'");
    }
    return telemetryRepository.history(id, fromTs, toTs, limit);
  }

  /** Recent ERROR events: full count in the window plus the most recent occurrences. */
  @GetMapping("/{id}/errors")
  public ErrorReport errors(
      @PathVariable String id, @RequestParam(defaultValue = "24h") String window) {
    deviceService.get(id); // 404 for unknown devices
    Duration parsed = WindowParser.parse(window);
    Instant since = deviceService.now().minus(parsed);
    return new ErrorReport(
        window,
        telemetryRepository.countErrors(id, since),
        telemetryRepository.errors(id, since, MAX_ERROR_EVENTS));
  }

  public record ErrorReport(String window, int count, List<ErrorEvent> latest) {}

  private static Instant parseInstant(String raw, Instant fallback) {
    if (raw == null || raw.isBlank()) {
      return fallback;
    }
    try {
      return OffsetDateTime.parse(raw.trim()).toInstant();
    } catch (DateTimeParseException e) {
      throw new IllegalArgumentException(
          "invalid timestamp '" + raw + "', expected ISO-8601 like 2026-06-12T10:00:00Z");
    }
  }
}
