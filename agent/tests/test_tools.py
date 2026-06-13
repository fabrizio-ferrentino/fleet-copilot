"""summarize() must stay compact and must not mistake data for failures."""

from __future__ import annotations

from agent.tools import SUMMARY_MAX_CHARS, summarize


def test_list_summary_counts_and_names_ids():
    payload = [{"deviceId": f"dev-{i:03d}"} for i in range(1, 9)]
    summary = summarize(payload)
    assert summary.startswith("8 results: dev-001")
    assert summary.endswith("…")


def test_fleet_status_error_count_is_not_a_failure():
    summary = summarize({"total": 50, "online": 30, "offline": 20, "warning": 0, "error": 0})
    assert not summary.startswith("error:")
    assert '"error":0' in summary


def test_string_error_payload_is_a_failure():
    assert summarize({"error": "platform unreachable"}) == "error: platform unreachable"


def test_long_dicts_are_truncated():
    payload = {"k": "x" * 500}
    assert len(summarize(payload)) <= SUMMARY_MAX_CHARS
