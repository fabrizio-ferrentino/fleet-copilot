package com.fleetcopilot.ingestion;

import com.fleetcopilot.domain.DeviceRepository;
import com.fleetcopilot.domain.TelemetryReading;
import com.fleetcopilot.domain.TelemetryRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Persists one validated reading: append to the telemetry history and refresh the device snapshot,
 * atomically, so the snapshot can never reference a reading that was not stored.
 */
@Service
public class TelemetryIngestionService {

  private final TelemetryRepository telemetryRepository;
  private final DeviceRepository deviceRepository;

  public TelemetryIngestionService(
      TelemetryRepository telemetryRepository, DeviceRepository deviceRepository) {
    this.telemetryRepository = telemetryRepository;
    this.deviceRepository = deviceRepository;
  }

  @Transactional
  public void ingest(TelemetryReading reading) {
    telemetryRepository.insert(reading);
    deviceRepository.upsert(reading);
  }
}
