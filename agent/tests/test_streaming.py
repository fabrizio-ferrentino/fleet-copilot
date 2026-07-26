"""Streaming (SSE) events and provider error handling — no network, no Gemini."""

from __future__ import annotations

from collections.abc import Iterator, Sequence

import pytest
from fastapi.testclient import TestClient
from google.genai import errors

from agent.llm import LlmError, LlmTurn, ToolCall, ToolSpec, _send_with_retry
from agent.loop import AgentLoop, AnswerEvent, ErrorEvent, ToolEvent
from agent.main import create_app


class FakeConversation:
    def __init__(self, turns: list[LlmTurn]) -> None:
        self._turns = iter(turns)

    def send_user(self, text: str) -> LlmTurn:
        return next(self._turns)

    def send_tool_results(self, results: Sequence[object]) -> LlmTurn:
        return next(self._turns)


class FakeProvider:
    def __init__(self, turns: list[LlmTurn]) -> None:
        self._turns = turns

    def conversation(self, system_prompt: str, tools: Sequence[ToolSpec]):
        return FakeConversation(self._turns)


class RaisingProvider:
    def conversation(self, system_prompt: str, tools: Sequence[ToolSpec]):
        return self

    def send_user(self, text: str) -> LlmTurn:
        raise LlmError("provider down", retryable=True)


class RecordingTools:
    def specs(self) -> list[ToolSpec]:
        return [ToolSpec(name="get_fleet_status", description="d")]

    def execute(self, name: str, args: dict):
        return {"total": 3, "online": 2}


def test_ask_events_streams_tool_then_answer():
    provider = FakeProvider(
        [
            LlmTurn(text=None, tool_calls=[ToolCall("get_fleet_status", {})]),
            LlmTurn(text="2 of 3 devices are online."),
        ]
    )

    events = list(AgentLoop(provider, RecordingTools()).ask_events("how is the fleet?"))

    assert isinstance(events[0], ToolEvent)
    assert events[0].tool == "get_fleet_status"
    assert "online" in events[0].result_summary
    assert isinstance(events[-1], AnswerEvent)
    assert events[-1].text == "2 of 3 devices are online."


def test_provider_error_becomes_error_event_not_crash():
    loop = AgentLoop(RaisingProvider(), RecordingTools())

    events = list(loop.ask_events("hi"))

    assert len(events) == 1
    assert isinstance(events[0], ErrorEvent)
    assert events[0].retryable is True

    # ask() surfaces the error as the answer instead of raising
    result = loop.ask("hi")
    assert result.answer == "provider down"
    assert result.tool_trace == []


class StreamStubLoop:
    def ask_events(self, question: str) -> Iterator[object]:
        yield ToolEvent("get_fleet_status", {}, '{"total":3}')
        yield AnswerEvent("all good")


def test_ask_stream_emits_sse_frames():
    client = TestClient(create_app(loop=StreamStubLoop()))  # type: ignore[arg-type]

    response = client.post("/ask/stream", json={"question": "How is the fleet?"})

    assert response.status_code == 200
    assert "text/event-stream" in response.headers["content-type"]
    body = response.text
    assert "event: tool" in body
    assert "event: answer" in body
    assert "event: done" in body
    assert "get_fleet_status" in body


def _client_error(code: int) -> errors.ClientError:
    # Build a real ClientError without the version-specific constructor; the retry
    # logic only inspects `.code`.
    exc = errors.ClientError.__new__(errors.ClientError)
    exc.code = code  # type: ignore[attr-defined]
    return exc


def test_send_with_retry_maps_rate_limit_to_retryable(monkeypatch):
    monkeypatch.setattr("agent.llm.time.sleep", lambda *_: None)

    def call():
        raise _client_error(429)

    with pytest.raises(LlmError) as exc_info:
        _send_with_retry(call)
    assert exc_info.value.retryable is True


def test_send_with_retry_maps_client_error_to_non_retryable(monkeypatch):
    monkeypatch.setattr("agent.llm.time.sleep", lambda *_: None)

    def call():
        raise _client_error(400)

    with pytest.raises(LlmError) as exc_info:
        _send_with_retry(call)
    assert exc_info.value.retryable is False
