"""Device state machine: vehicle-like movement, battery cycle, temperature."""

from __future__ import annotations

import math
import random
from datetime import UTC, datetime

# Fleet starting area (Campania, Italy); devices spread around this point.
BASE_LAT = 40.78
BASE_LON = 14.59
START_SPREAD_DEG = 0.5

# Battery rates are per second so behaviour is independent of the publish interval.
DRAIN_PCT_PER_S = (0.002, 0.006)  # a full battery lasts roughly 5-14 h
CHARGE_PCT_PER_S = (0.05, 0.15)  # a full recharge takes roughly 10-30 min
SPONTANEOUS_CHARGE_P_PER_S = 0.00006  # on average a device recharges early every ~4-5 h
LOW_BATTERY_WARNING_PCT = 20.0

SPEED_KMH = (15.0, 70.0)
HEADING_JITTER_DEG = 12.0
KM_PER_DEG_LAT = 110.574
KM_PER_DEG_LON_AT_EQUATOR = 111.320

TEMP_BASELINE_C = (32.0, 48.0)
TEMP_NOISE_C = 1.2

FIRMWARE_VERSIONS = ("1.3.9", "1.4.1", "1.4.2")


def _iso_utc_now() -> str:
    return datetime.now(UTC).isoformat(timespec="seconds").replace("+00:00", "Z")


class Device:
    """One simulated device; ``tick`` advances its state and returns a telemetry payload."""

    def __init__(self, device_id: str, rng: random.Random) -> None:
        self.device_id = device_id
        self._rng = rng
        self.lat = BASE_LAT + rng.uniform(-START_SPREAD_DEG, START_SPREAD_DEG)
        self.lon = BASE_LON + rng.uniform(-START_SPREAD_DEG, START_SPREAD_DEG)
        self.battery_pct = rng.uniform(35.0, 100.0)
        self.firmware = rng.choice(FIRMWARE_VERSIONS)
        self._heading_deg = rng.uniform(0.0, 360.0)
        self._speed_kmh = rng.uniform(*SPEED_KMH)
        self._charging = False
        self._charge_below_pct = rng.uniform(5.0, 15.0)
        self._drain_pct_per_s = rng.uniform(*DRAIN_PCT_PER_S)
        self._charge_pct_per_s = rng.uniform(*CHARGE_PCT_PER_S)
        self._temp_baseline_c = rng.uniform(*TEMP_BASELINE_C)

    def tick(self, elapsed_s: float) -> dict:
        """Advance the state by ``elapsed_s`` seconds and build the telemetry payload."""
        self._move(elapsed_s)
        self._update_battery(elapsed_s)
        temperature_c = self._temp_baseline_c + self._rng.gauss(0.0, TEMP_NOISE_C)
        status = "WARNING" if self.battery_pct < LOW_BATTERY_WARNING_PCT else "OK"
        return {
            "deviceId": self.device_id,
            "ts": _iso_utc_now(),
            "lat": round(self.lat, 5),
            "lon": round(self.lon, 5),
            "batteryPct": round(self.battery_pct, 1),
            "temperatureC": round(temperature_c, 1),
            "status": status,
            "errorCode": None,
            "firmware": self.firmware,
        }

    def _move(self, elapsed_s: float) -> None:
        """Slow random walk: keep a heading, jitter it a little, advance at vehicle speed."""
        self._heading_deg = (self._heading_deg + self._rng.gauss(0.0, HEADING_JITTER_DEG)) % 360.0
        distance_km = self._speed_kmh * elapsed_s / 3600.0
        heading_rad = math.radians(self._heading_deg)
        self.lat += distance_km * math.cos(heading_rad) / KM_PER_DEG_LAT
        self.lat = max(-85.0, min(85.0, self.lat))
        self.lon += (
            distance_km
            * math.sin(heading_rad)
            / (KM_PER_DEG_LON_AT_EQUATOR * math.cos(math.radians(self.lat)))
        )
        self.lon = (self.lon + 180.0) % 360.0 - 180.0

    def _update_battery(self, elapsed_s: float) -> None:
        if self._charging:
            self.battery_pct = min(100.0, self.battery_pct + self._charge_pct_per_s * elapsed_s)
            if self.battery_pct >= 100.0:
                self._charging = False
            return
        self.battery_pct = max(0.0, self.battery_pct - self._drain_pct_per_s * elapsed_s)
        starts_early = self._rng.random() < SPONTANEOUS_CHARGE_P_PER_S * elapsed_s
        if self.battery_pct <= self._charge_below_pct or starts_early:
            self._charging = True
