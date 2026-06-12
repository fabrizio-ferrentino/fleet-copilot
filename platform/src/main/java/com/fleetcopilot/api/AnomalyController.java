package com.fleetcopilot.api;

import com.fleetcopilot.anomaly.AnomalyFinding;
import com.fleetcopilot.anomaly.AnomalyService;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class AnomalyController {

  private final AnomalyService anomalyService;

  public AnomalyController(AnomalyService anomalyService) {
    this.anomalyService = anomalyService;
  }

  @GetMapping("/api/anomalies")
  public List<AnomalyFinding> anomalies(@RequestParam(defaultValue = "24h") String window) {
    return anomalyService.detect(WindowParser.parse(window));
  }
}
