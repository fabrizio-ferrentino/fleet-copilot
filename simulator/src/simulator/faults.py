"""Fault definitions, control-message parsing and fault behaviour parameters.

Faults are applied per device, either at startup (CLI/env) or at runtime by
publishing to the ``fleet/control`` topic, e.g.::

    {"deviceId": "dev-042", "fault": "silent"}

Publishing ``{"deviceId": "dev-042", "fault": "clear"}`` removes the fault.
"""

from __future__ import annotations

import json

SILENT = "silent"
BATTERY_DRAIN = "battery_drain"
GPS_DRIFT = "gps_drift"
ERROR_BURST = "error_burst"
CLEAR = "clear"

FAULTS = (SILENT, BATTERY_DRAIN, GPS_DRIFT, ERROR_BURST)

CONTROL_TOPIC = "fleet/control"

# Behaviour parameters
DRAIN_MULTIPLIER = 60.0  # battery_drain: drains in minutes instead of hours
DRIFT_JUMP_DEG = (0.05, 0.15)  # gps_drift: per-tick jump, implies thousands of km/h
ERROR_CODES = ("E-PWR-03", "E-SENS-12", "E-COMM-07")


def parse_control(payload: bytes | str) -> tuple[str, str]:
    """Parse a fleet/control message into (device_id, fault); raises ValueError."""
    try:
        data = json.loads(payload)
    except json.JSONDecodeError as exc:
        raise ValueError(f"malformed JSON: {exc}") from exc
    if not isinstance(data, dict):
        raise ValueError("control message must be a JSON object")
    device_id = data.get("deviceId")
    fault = data.get("fault")
    if not isinstance(device_id, str) or not device_id:
        raise ValueError("missing or invalid 'deviceId'")
    if fault not in FAULTS and fault != CLEAR:
        raise ValueError(f"unknown fault '{fault}', expected one of {FAULTS + (CLEAR,)}")
    return device_id, fault


def parse_fault_spec(spec: str) -> tuple[str, str]:
    """Parse a startup fault spec like ``dev-042:silent``; raises ValueError."""
    device_id, sep, fault = spec.partition(":")
    if not sep or not device_id or fault not in FAULTS:
        raise ValueError(f"invalid fault spec '{spec}', expected DEVICE_ID:FAULT in {FAULTS}")
    return device_id, fault
