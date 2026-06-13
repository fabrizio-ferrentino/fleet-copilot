"""Golden tests: real Gemini, mocked tool layer, asserting tool-call patterns.

These verify the model picks the right tool for a question (the heart of an agent).
They need a live LLM, so they skip automatically when GEMINI_API_KEY is absent — the
deterministic loop/tool/API unit tests are what keep CI green without a key.

Run locally with:  GEMINI_API_KEY=... pytest agent/tests/test_golden.py -q
"""

from __future__ import annotations

import os
from typing import Any

import pytest

from agent.llm import GeminiProvider, ToolSpec
from agent.loop import AgentLoop
from agent.tools import SPECS

pytestmark = pytest.mark.skipif(
    not os.getenv("GEMINI_API_KEY"), reason="golden tests need a live GEMINI_API_KEY"
)

# Canned responses so the mocked tool layer satisfies the model without a real platform.
CANNED: dict[str, Any] = {
    "get_fleet_status": {"total": 50, "online": 47, "offline": 3, "warning": 1, "error": 1},
    "list_devices": [
        {"id": "dev-031", "online": False, "status": "OK", "lastSeen": "2026-06-13T09:00:00Z"}
    ],
    "detect_anomalies": [
        {
            "deviceId": "dev-031",
            "rule": "SILENT",
            "severity": "HIGH",
            "evidence": {"silentForSeconds": 900},
        }
    ],
    "get_device": {"id": "dev-042", "online": True, "status": "OK", "batteryPct": 73.1},
    "get_device_history": [
        {"ts": "2026-06-13T10:00:00Z", "batteryPct": 73.1, "status": "OK"}
    ],
    "get_device_errors": {
        "window": "24h",
        "count": 12,
        "latest": [{"ts": "2026-06-13T10:00:00Z", "errorCode": "E-PWR-03"}],
    },
}


class RecordingTools:
    """Real tool specs (so the model sees the true catalog), canned results, records calls."""

    def __init__(self) -> None:
        self.calls: list[tuple[str, dict]] = []

    def specs(self) -> list[ToolSpec]:
        return SPECS

    def execute(self, name: str, args: dict) -> Any:
        self.calls.append((name, args))
        return CANNED.get(name, {"error": f"unknown tool '{name}'"})


def run(question: str) -> RecordingTools:
    tools = RecordingTools()
    answer = AgentLoop(GeminiProvider(), tools).ask(question)
    assert answer.answer  # the model must produce some grounded final answer
    return tools


def called(tools: RecordingTools, name: str) -> list[dict]:
    return [args for (n, args) in tools.calls if n == name]


def test_offline_devices_calls_list_devices_with_status_offline():
    tools = run("Which devices are offline right now?")
    offline_calls = called(tools, "list_devices")
    assert offline_calls, f"expected list_devices, got {tools.calls}"
    assert any(c.get("status") == "offline" for c in offline_calls)


def test_fleet_overview_calls_get_fleet_status():
    tools = run("How many devices are online versus offline in the fleet?")
    assert called(tools, "get_fleet_status"), f"expected get_fleet_status, got {tools.calls}"


def test_recent_anomalies_calls_detect_anomalies():
    tools = run("Is anything anomalous in the last hour?")
    assert called(tools, "detect_anomalies"), f"expected detect_anomalies, got {tools.calls}"


def test_single_device_state_calls_get_device():
    tools = run("What is the current state of device dev-042?")
    device_calls = called(tools, "get_device")
    assert device_calls, f"expected get_device, got {tools.calls}"
    assert any(c.get("deviceId") == "dev-042" for c in device_calls)


def test_device_errors_calls_get_device_errors():
    tools = run("Show me the recent error events for dev-007.")
    error_calls = called(tools, "get_device_errors")
    assert error_calls, f"expected get_device_errors, got {tools.calls}"
    assert any(c.get("deviceId") == "dev-007" for c in error_calls)


def test_device_history_calls_get_device_history():
    tools = run("What has dev-013 been reporting over its recent readings?")
    history_calls = called(tools, "get_device_history")
    assert history_calls, f"expected get_device_history, got {tools.calls}"
    assert any(c.get("deviceId") == "dev-013" for c in history_calls)
