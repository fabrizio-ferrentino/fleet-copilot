package com.fleetcopilot.api;

import com.fleetcopilot.domain.DeviceFilter;
import com.fleetcopilot.domain.DeviceService;
import com.fleetcopilot.domain.DeviceView;
import com.fleetcopilot.ingestion.MqttControlPublisher;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Demo control plane: inject (or clear) a fault on a device by publishing to the MQTT control topic
 * the simulator listens on, so the UI can break the fleet without a shell. With no deviceId a
 * random online device is picked and returned, so a single button click produces a visible effect.
 */
@RestController
@RequestMapping("/api/control")
public class ControlController {

  private static final Set<String> FAULTS =
      Set.of("silent", "battery_drain", "gps_drift", "error_burst", "clear");

  private final DeviceService deviceService;
  private final MqttControlPublisher publisher;

  public ControlController(DeviceService deviceService, MqttControlPublisher publisher) {
    this.deviceService = deviceService;
    this.publisher = publisher;
  }

  @PostMapping("/fault")
  public FaultResponse injectFault(@RequestBody FaultRequest request) {
    String fault = request.fault() == null ? "" : request.fault().trim();
    if (!FAULTS.contains(fault)) {
      throw new IllegalArgumentException(
          "unknown fault '" + fault + "', expected one of " + FAULTS);
    }
    String deviceId = request.deviceId() == null ? "" : request.deviceId().trim();
    if (deviceId.isEmpty()) {
      deviceId = randomOnlineDeviceId();
    }
    publisher.publishFault(deviceId, fault);
    return new FaultResponse(deviceId, fault);
  }

  private String randomOnlineDeviceId() {
    List<DeviceView> online = deviceService.list(DeviceFilter.ONLINE);
    if (online.isEmpty()) {
      throw new IllegalStateException("no online device to inject a fault into");
    }
    return online.get(ThreadLocalRandom.current().nextInt(online.size())).id();
  }

  public record FaultRequest(String deviceId, String fault) {}

  public record FaultResponse(String deviceId, String fault) {}
}
