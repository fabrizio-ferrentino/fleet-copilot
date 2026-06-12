"""Executor -> REST mapping, checked with an httpx mock transport (no real platform)."""

from __future__ import annotations

import httpx

from agent.tools import PlatformTools


def tools_capturing(requests: list[httpx.Request]) -> PlatformTools:
    def handler(request: httpx.Request) -> httpx.Response:
        requests.append(request)
        return httpx.Response(200, json={"ok": True})

    client = httpx.Client(transport=httpx.MockTransport(handler))
    return PlatformTools("http://platform:8080", client)


def test_get_device_builds_path_from_device_id():
    seen: list[httpx.Request] = []
    tools_capturing(seen).execute("get_device", {"deviceId": "dev-042"})

    assert seen[0].url.path == "/api/devices/dev-042"


def test_history_caps_limit_and_targets_telemetry():
    seen: list[httpx.Request] = []
    tools_capturing(seen).execute("get_device_history", {"deviceId": "dev-042", "limit": 9999})

    assert seen[0].url.path == "/api/devices/dev-042/telemetry"
    assert seen[0].url.params["limit"] == "200"


def test_errors_passes_window_through():
    seen: list[httpx.Request] = []
    tools_capturing(seen).execute("get_device_errors", {"deviceId": "dev-007", "window": "1h"})

    assert seen[0].url.path == "/api/devices/dev-007/errors"
    assert seen[0].url.params["window"] == "1h"
