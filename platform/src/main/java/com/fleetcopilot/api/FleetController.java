package com.fleetcopilot.api;

import com.fleetcopilot.domain.DeviceService;
import com.fleetcopilot.domain.FleetStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/fleet")
public class FleetController {

  private final DeviceService deviceService;

  public FleetController(DeviceService deviceService) {
    this.deviceService = deviceService;
  }

  @GetMapping("/status")
  public FleetStatus status() {
    return deviceService.fleetStatus();
  }
}
