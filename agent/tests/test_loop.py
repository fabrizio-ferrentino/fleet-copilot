"""Loop mechanics, tested with a scripted fake provider (no network, no Gemini)."""

from __future__ import annotations

from collections.abc import Sequence

from agent.llm import LlmTurn, ToolCall, ToolResult, ToolSpec
from agent.loop import MAX_TOOL_CALLS, AgentLoop


class FakeConversation:
    """Replays a scripted list of model turns and records what the loop sends back."""

    def __init__(self, turns: list[LlmTurn]) -> None:
        self._turns = iter(turns)
        self.received_results: list[list[ToolResult]] = []

    def send_user(self, text: str) -> LlmTurn:
        return next(self._turns)

    def send_tool_results(self, results: Sequence[ToolResult]) -> LlmTurn:
        self.received_results.append(list(results))
        return next(self._turns)


class FakeProvider:
    def __init__(self, turns: list[LlmTurn]) -> None:
        self.conversation_obj = FakeConversation(turns)

    def conversation(self, system_prompt: str, tools: Sequence[ToolSpec]):
        return self.conversation_obj


class RecordingTools:
    def __init__(self, result=None) -> None:
        self.calls: list[tuple[str, dict]] = []
        self._result = result if result is not None else {"total": 3, "online": 2}

    def specs(self) -> list[ToolSpec]:
        return [ToolSpec(name="get_fleet_status", description="d")]

    def execute(self, name: str, args: dict):
        self.calls.append((name, args))
        return self._result


def test_executes_tools_and_returns_grounded_answer():
    provider = FakeProvider(
        [
            LlmTurn(text=None, tool_calls=[ToolCall("get_fleet_status", {})]),
            LlmTurn(text="2 of 3 devices are online."),
        ]
    )
    tools = RecordingTools()

    result = AgentLoop(provider, tools).ask("How is the fleet?")

    assert result.answer == "2 of 3 devices are online."
    assert tools.calls == [("get_fleet_status", {})]
    assert len(result.tool_trace) == 1
    assert result.tool_trace[0].tool == "get_fleet_status"
    assert "online" in result.tool_trace[0].result_summary
    # the tool result was fed back to the model
    assert provider.conversation_obj.received_results[0][0].payload == {"total": 3, "online": 2}


def test_stops_executing_after_six_tool_calls():
    hungry_turns = [
        LlmTurn(text=None, tool_calls=[ToolCall("get_fleet_status", {})]) for _ in range(7)
    ]
    provider = FakeProvider([*hungry_turns, LlmTurn(text="done with what I have")])
    tools = RecordingTools()

    result = AgentLoop(provider, tools).ask("loop forever please")

    assert tools.calls and len(tools.calls) == MAX_TOOL_CALLS
    assert result.answer == "done with what I have"
    refused = provider.conversation_obj.received_results[-1][0]
    assert "budget" in str(refused.payload.get("error"))


def test_gives_up_after_too_many_rounds():
    endless = [LlmTurn(text=None, tool_calls=[ToolCall("get_fleet_status", {})]) for _ in range(20)]
    provider = FakeProvider(endless)
    tools = RecordingTools()

    result = AgentLoop(provider, tools).ask("never answer")

    assert "Stopped" in result.answer


def test_unknown_tool_error_is_reported_not_raised():
    provider = FakeProvider(
        [
            LlmTurn(text=None, tool_calls=[ToolCall("fly_to_the_moon", {})]),
            LlmTurn(text="I cannot do that."),
        ]
    )

    class RealTools(RecordingTools):
        def execute(self, name: str, args: dict):
            self.calls.append((name, args))
            return {"error": f"unknown tool '{name}'"}

    tools = RealTools()
    result = AgentLoop(provider, tools).ask("fly")

    assert result.answer == "I cannot do that."
    assert result.tool_trace[0].result_summary.startswith("error:")
