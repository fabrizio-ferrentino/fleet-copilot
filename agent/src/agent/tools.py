"""Tool definitions, 1:1 with the platform REST API.

Every executor returns JSON-serializable data; failures (platform down, HTTP
errors) come back as {"error": ...} payloads so the model can tell the user it
could not investigate instead of the loop crashing.
"""

from __future__ import annotations

import json
from typing import Any

import httpx

from agent.llm import ToolSpec

REQUEST_TIMEOUT_S = 10.0
SUMMARY_MAX_CHARS = 160
SUMMARY_MAX_IDS = 5
DEFAULT_PLATFORM_URL = "http://localhost:8080"
HISTORY_DEFAULT_LIMIT = 20
HISTORY_MAX_LIMIT = 200

SPECS: list[ToolSpec] = [
    ToolSpec(
        name="get_fleet_status",
        description=(
            "Fleet-wide totals right now: how many devices exist and how many are online, "
            "offline, in warning or in error. Start here for any fleet-health question."
        ),
    ),
    ToolSpec(
        name="list_devices",
        description=(
            "List devices with their current state (id, online, lastSeen, status, batteryPct, "
            "lat, lon, firmware). Optionally filter with 'status'."
        ),
        parameters={
            "type": "OBJECT",
            "properties": {
                "status": {
                    "type": "STRING",
                    "enum": ["online", "offline", "ok", "warning", "error"],
                    "description": (
                        "Filter: offline = silent for too long; "
                        "ok/warning/error = last reported status."
                    ),
                }
            },
        },
    ),
    ToolSpec(
        name="detect_anomalies",
        description=(
            "Run rule-based anomaly detection over a time window. Rules: SILENT (device stopped "
            "reporting), BATTERY_DROP (>20 points in 1h), GPS_JUMP (implied speed >200 km/h), "
            "HIGH_TEMPERATURE (>70C). Each finding carries evidence (timestamps, values)."
        ),
        parameters={
            "type": "OBJECT",
            "properties": {
                "window": {
                    "type": "STRING",
                    "description": (
                        "Lookback window like 30s, 15m, 1h, 24h, 7d (max 7d); default 24h."
                    ),
                }
            },
        },
    ),
    ToolSpec(
        name="get_device",
        description=(
            "Current state of one device: online flag, lastSeen, status, battery, position, "
            "firmware."
        ),
        parameters={
            "type": "OBJECT",
            "properties": {
                "deviceId": {"type": "STRING", "description": "Device id, e.g. dev-042."}
            },
            "required": ["deviceId"],
        },
    ),
    ToolSpec(
        name="get_device_history",
        description=(
            "Recent telemetry readings of one device, newest first: ts, lat, lon, batteryPct, "
            "temperatureC, status, errorCode. Use it to see how a device behaved over time."
        ),
        parameters={
            "type": "OBJECT",
            "properties": {
                "deviceId": {"type": "STRING", "description": "Device id, e.g. dev-042."},
                "limit": {
                    "type": "INTEGER",
                    "description": (
                        f"How many readings (default {HISTORY_DEFAULT_LIMIT}, "
                        f"max {HISTORY_MAX_LIMIT})."
                    ),
                },
            },
            "required": ["deviceId"],
        },
    ),
    ToolSpec(
        name="get_device_errors",
        description=(
            "ERROR events reported by one device within a window: total count plus the most "
            "recent occurrences with their error codes."
        ),
        parameters={
            "type": "OBJECT",
            "properties": {
                "deviceId": {"type": "STRING", "description": "Device id, e.g. dev-042."},
                "window": {
                    "type": "STRING",
                    "description": "Lookback window like 15m, 1h, 24h, 7d; default 24h.",
                },
            },
            "required": ["deviceId"],
        },
    ),
]


class PlatformTools:
    """Executes tool calls against the platform REST API."""

    def __init__(self, base_url: str, client: httpx.Client | None = None) -> None:
        self._base_url = base_url.rstrip("/")
        self._client = client or httpx.Client(timeout=REQUEST_TIMEOUT_S)

    def specs(self) -> list[ToolSpec]:
        return SPECS

    def execute(self, name: str, args: dict[str, Any]) -> Any:
        match name:
            case "get_fleet_status":
                return self._get("/api/fleet/status")
            case "list_devices":
                params = {"status": args["status"]} if args.get("status") else None
                return self._get("/api/devices", params)
            case "detect_anomalies":
                return self._get("/api/anomalies", {"window": args.get("window", "24h")})
            case "get_device":
                return self._get(f"/api/devices/{args.get('deviceId', '')}")
            case "get_device_history":
                limit = min(int(args.get("limit", HISTORY_DEFAULT_LIMIT)), HISTORY_MAX_LIMIT)
                return self._get(
                    f"/api/devices/{args.get('deviceId', '')}/telemetry", {"limit": limit}
                )
            case "get_device_errors":
                return self._get(
                    f"/api/devices/{args.get('deviceId', '')}/errors",
                    {"window": args.get("window", "24h")},
                )
            case _:
                return {"error": f"unknown tool '{name}'"}

    def _get(self, path: str, params: dict[str, Any] | None = None) -> Any:
        try:
            response = self._client.get(self._base_url + path, params=params)
        except httpx.HTTPError as exc:
            return {"error": f"platform request failed: {exc}"}
        if response.status_code >= 400:
            return {"error": f"platform returned {response.status_code}: {response.text[:200]}"}
        return response.json()


def summarize(payload: Any) -> str:
    """Compact, human-readable summary of a tool result for the trace."""
    if isinstance(payload, list):
        ids = [item.get("deviceId") or item.get("id") for item in payload if isinstance(item, dict)]
        ids = [i for i in ids if i][:SUMMARY_MAX_IDS]
        suffix = f": {', '.join(ids)}" if ids else ""
        if len(payload) > len(ids) and ids:
            suffix += ", …"
        return f"{len(payload)} results{suffix}"
    # tool failures carry a string message; e.g. fleet status has a numeric "error" *count*
    if isinstance(payload, dict) and isinstance(payload.get("error"), str):
        return f"error: {payload['error']}"
    text = json.dumps(payload, separators=(",", ":"), default=str)
    return text if len(text) <= SUMMARY_MAX_CHARS else text[: SUMMARY_MAX_CHARS - 1] + "…"
