"""The agentic loop: question -> tool calls (max 6) -> grounded final answer."""

from __future__ import annotations

from dataclasses import dataclass, field
from typing import Any

from agent.llm import LlmProvider, ToolResult
from agent.tools import PlatformTools, summarize

MAX_TOOL_CALLS = 6
MAX_ROUNDS = 8  # hard stop against a model that never produces a final answer

SYSTEM_PROMPT = """\
You are Fleet Copilot, a diagnostic assistant for a fleet of IoT devices.

Rules:
- Answer ONLY from data returned by your tools. Never invent devices, values or timestamps.
- If the tools do not return enough data to answer, say so explicitly.
- Be concise, with a diagnostic tone. When you state a finding, cite the evidence:
  device ids, timestamps and the relevant numbers from the tool results.
- All timestamps are UTC, ISO-8601.
"""

BUDGET_EXHAUSTED = {
    "error": "tool-call budget exhausted; answer now from the data you already have"
}


@dataclass(frozen=True)
class TraceEntry:
    tool: str
    args: dict[str, Any]
    result_summary: str


@dataclass(frozen=True)
class AgentAnswer:
    answer: str
    tool_trace: list[TraceEntry] = field(default_factory=list)


class AgentLoop:
    def __init__(self, provider: LlmProvider, tools: PlatformTools) -> None:
        self._provider = provider
        self._tools = tools

    def ask(self, question: str) -> AgentAnswer:
        conversation = self._provider.conversation(SYSTEM_PROMPT, self._tools.specs())
        trace: list[TraceEntry] = []
        executed = 0

        turn = conversation.send_user(question)
        for _ in range(MAX_ROUNDS):
            if not turn.tool_calls:
                return AgentAnswer(turn.text or "(the model returned no answer)", trace)
            results: list[ToolResult] = []
            for call in turn.tool_calls:  # executed sequentially, in the order requested
                if executed >= MAX_TOOL_CALLS:
                    payload: Any = BUDGET_EXHAUSTED
                else:
                    executed += 1
                    payload = self._tools.execute(call.name, call.args)
                trace.append(TraceEntry(call.name, call.args, summarize(payload)))
                results.append(ToolResult(call.name, payload))
            turn = conversation.send_tool_results(results)

        return AgentAnswer(
            turn.text or "Stopped: too many tool rounds without a final answer.", trace
        )
