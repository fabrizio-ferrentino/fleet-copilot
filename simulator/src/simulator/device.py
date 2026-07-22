"""Device state machine: vehicle-like movement, battery cycle, temperature."""

from __future__ import annotations

import math
import random
from datetime import UTC, datetime

from simulator import faults

# Real Campania towns (lat, lon), all comfortably inland, used as fleet home bases so that
# devices spawn and roam over the region's towns and roads — not the Tyrrhenian Sea.
HOME_BASES: tuple[tuple[float, float], ...] = (
    (41.08, 14.33),  # Caserta
    (41.03, 14.30),  # Marcianise
    (40.97, 14.21),  # Aversa
    (41.10, 14.21),  # Capua
    (41.03, 14.39),  # Maddaloni
    (40.95, 14.37),  # Acerra
    (40.92, 14.31),  # Afragola
    (40.93, 14.53),  # Nola
    (40.76, 14.55),  # Palma Campania
    (41.06, 14.64),  # Montesarchio
    (41.13, 14.78),  # Benevento
    (40.91, 14.79),  # Avellino
    (40.92, 14.84),  # Atripalda
    (40.81, 14.62),  # Sarno
    (40.74, 14.64),  # Nocera Inferiore
    (40.62, 15.05),  # Eboli
)
SPAWN_JITTER_DEG = 0.03  # ~3 km scatter around a home base at spawn
ROAM_RADIUS_DEG = 0.05  # stay within ~5 km of home, so a slow walk never reaches the coast

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
        self._home_lat, self._home_lon = rng.choice(HOME_BASES)
        self.lat = self._home_lat + rng.uniform(-SPAWN_JITTER_DEG, SPAWN_JITTER_DEG)
        self.lon = self._home_lon + rng.uniform(-SPAWN_JITTER_DEG, SPAWN_JITTER_DEG)
        self.battery_pct = rng.uniform(35.0, 100.0)
        self.firmware = rng.choice(FIRMWARE_VERSIONS)
        self.fault: str | None = None
        self._error_code: str | None = None
        self._heading_deg = rng.uniform(0.0, 360.0)
        self._speed_kmh = rng.uniform(*SPEED_KMH)
        self._charging = False
        self._charge_below_pct = rng.uniform(5.0, 15.0)
        self._drain_pct_per_s = rng.uniform(*DRAIN_PCT_PER_S)
        self._charge_pct_per_s = rng.uniform(*CHARGE_PCT_PER_S)
        self._temp_baseline_c = rng.uniform(*TEMP_BASELINE_C)

    def set_fault(self, fault: str | None) -> None:
        """Apply a fault (or clear it with None); effects show up from the next tick."""
        self.fault = fault
        self._error_code = (
            self._rng.choice(faults.ERROR_CODES) if fault == faults.ERROR_BURST else None
        )

    def tick(self, elapsed_s: float) -> dict | None:
        """Advance the state by ``elapsed_s`` seconds and build the telemetry payload.

        Returns None when the device is silenced by a fault (nothing to publish).
        """
        if self.fault == faults.SILENT:
            return None
        self._move(elapsed_s)
        self._update_battery(elapsed_s)
        temperature_c = self._temp_baseline_c + self._rng.gauss(0.0, TEMP_NOISE_C)
        if self.fault == faults.ERROR_BURST:
            status, error_code = "ERROR", self._error_code
        elif self.battery_pct < LOW_BATTERY_WARNING_PCT:
            status, error_code = "WARNING", None
        else:
            status, error_code = "OK", None
        return {
            "deviceId": self.device_id,
            "ts": _iso_utc_now(),
            "lat": round(self.lat, 5),
            "lon": round(self.lon, 5),
            "batteryPct": round(self.battery_pct, 1),
            "temperatureC": round(temperature_c, 1),
            "status": status,
            "errorCode": error_code,
            "firmware": self.firmware,
        }

    def _move(self, elapsed_s: float) -> None:
        """Slow random walk: keep a heading, jitter it a little, advance at vehicle speed."""
        if self.fault == faults.GPS_DRIFT:
            # Unrealistic jump: kilometres per tick, far beyond any plausible speed.
            self.lat += self._rng.choice((-1.0, 1.0)) * self._rng.uniform(*faults.DRIFT_JUMP_DEG)
            self.lon += self._rng.choice((-1.0, 1.0)) * self._rng.uniform(*faults.DRIFT_JUMP_DEG)
            self.lat = max(-85.0, min(85.0, self.lat))
            self.lon = (self.lon + 180.0) % 360.0 - 180.0
            return
        self._heading_deg = (self._heading_deg + self._rng.gauss(0.0, HEADING_JITTER_DEG)) % 360.0
        distance_km = self._speed_kmh * elapsed_s / 3600.0
        heading_rad = math.radians(self._heading_deg)
        self.lat += distance_km * math.cos(heading_rad) / KM_PER_DEG_LAT
        self.lon += (
            distance_km
            * math.sin(heading_rad)
            / (KM_PER_DEG_LON_AT_EQUATOR * math.cos(math.radians(self.lat)))
        )
        self._keep_near_home()

    def _keep_near_home(self) -> None:
        """Clamp to a small box around the home base and turn back at the edge, so the slow
        random walk stays on land near its town instead of drifting into the sea."""
        bounced = False
        if self.lat > self._home_lat + ROAM_RADIUS_DEG:
            self.lat = self._home_lat + ROAM_RADIUS_DEG
            bounced = True
        elif self.lat < self._home_lat - ROAM_RADIUS_DEG:
            self.lat = self._home_lat - ROAM_RADIUS_DEG
            bounced = True
        if self.lon > self._home_lon + ROAM_RADIUS_DEG:
            self.lon = self._home_lon + ROAM_RADIUS_DEG
            bounced = True
        elif self.lon < self._home_lon - ROAM_RADIUS_DEG:
            self.lon = self._home_lon - ROAM_RADIUS_DEG
            bounced = True
        if bounced:
            self._heading_deg = (self._heading_deg + 180.0) % 360.0

    def _update_battery(self, elapsed_s: float) -> None:
        if self.fault == faults.BATTERY_DRAIN:
            # Abnormally fast drain; charging never kicks in while the fault is active.
            self._charging = False
            drain = self._drain_pct_per_s * faults.DRAIN_MULTIPLIER * elapsed_s
            self.battery_pct = max(0.0, self.battery_pct - drain)
            return
        if self._charging:
            self.battery_pct = min(100.0, self.battery_pct + self._charge_pct_per_s * elapsed_s)
            if self.battery_pct >= 100.0:
                self._charging = False
            return
        self.battery_pct = max(0.0, self.battery_pct - self._drain_pct_per_s * elapsed_s)
        starts_early = self._rng.random() < SPONTANEOUS_CHARGE_P_PER_S * elapsed_s
        if self.battery_pct <= self._charge_below_pct or starts_early:
            self._charging = True
