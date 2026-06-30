"""The agentic loop: question -> tool calls (max 6) -> grounded final answer.

The loop is expressed as a generator of events (``ask_events``) so the same
investigation can be streamed to a UI token-by-step or collected into a single
``AgentAnswer`` (``ask``) for the REST contract and the CLI.
"""

from __future__ import annotations

from collections.abc import Iterator
from dataclasses import dataclass, field
from typing import Any

from agent.llm import LlmError, LlmProvider, ToolResult
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
class ToolEvent:
    """A tool was executed during the investigation."""

    tool: str
    args: dict[str, Any]
    result_summary: str


@dataclass(frozen=True)
class AnswerEvent:
    """The final, grounded answer."""

    text: str


@dataclass(frozen=True)
class ErrorEvent:
    """The investigation could not complete (e.g. the LLM provider is unavailable)."""

    message: str
    retryable: bool


AgentEvent = ToolEvent | AnswerEvent | ErrorEvent


@dataclass(frozen=True)
class AgentAnswer:
    answer: str
    tool_trace: list[TraceEntry] = field(default_factory=list)


class AgentLoop:
    def __init__(self, provider: LlmProvider, tools: PlatformTools) -> None:
        self._provider = provider
        self._tools = tools

    def ask_events(self, question: str) -> Iterator[AgentEvent]:
        """Run the investigation, yielding one event per tool call and a final answer.

        Provider failures are surfaced as an ``ErrorEvent`` rather than raised, so a
        consumer (streaming endpoint or ``ask``) never crashes.
        """
        conversation = self._provider.conversation(SYSTEM_PROMPT, self._tools.specs())
        executed = 0

        try:
            turn = conversation.send_user(question)
            for _ in range(MAX_ROUNDS):
                if not turn.tool_calls:
                    yield AnswerEvent(turn.text or "(the model returned no answer)")
                    return
                results: list[ToolResult] = []
                for call in turn.tool_calls:  # executed sequentially, in the order requested
                    if executed >= MAX_TOOL_CALLS:
                        payload: Any = BUDGET_EXHAUSTED
                    else:
                        executed += 1
                        payload = self._tools.execute(call.name, call.args)
                    summary = summarize(payload)
                    yield ToolEvent(call.name, call.args, summary)
                    results.append(ToolResult(call.name, payload))
                turn = conversation.send_tool_results(results)

            yield AnswerEvent(turn.text or "Stopped: too many tool rounds without a final answer.")
        except LlmError as exc:
            yield ErrorEvent(exc.user_message, exc.retryable)

    def ask(self, question: str) -> AgentAnswer:
        """Collect the streamed events into a single answer (REST contract / CLI)."""
        answer = "(the model returned no answer)"
        trace: list[TraceEntry] = []
        for event in self.ask_events(question):
            if isinstance(event, ToolEvent):
                trace.append(TraceEntry(event.tool, event.args, event.result_summary))
            elif isinstance(event, AnswerEvent):
                answer = event.text
            elif isinstance(event, ErrorEvent):
                answer = event.message
        return AgentAnswer(answer, trace)
