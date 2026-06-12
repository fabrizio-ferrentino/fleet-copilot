"""Provider-agnostic LLM interface and the Gemini implementation.

The agentic loop only ever sees the neutral types below (ToolSpec, LlmTurn,
Conversation, ...), so swapping the provider means writing one new class here.
Per the project non-goals, Gemini is the only implemented provider.
"""

from __future__ import annotations

import os
from collections.abc import Sequence
from dataclasses import dataclass, field
from typing import Any, Protocol

from google import genai
from google.genai import types

DEFAULT_MODEL = "gemini-2.5-flash"


@dataclass(frozen=True)
class ToolSpec:
    """A tool the model may call; parameters is a JSON-schema object (or None for no args)."""

    name: str
    description: str
    parameters: dict[str, Any] | None = None


@dataclass(frozen=True)
class ToolCall:
    name: str
    args: dict[str, Any]


@dataclass(frozen=True)
class ToolResult:
    name: str
    payload: Any


@dataclass(frozen=True)
class LlmTurn:
    """One model turn: either tool calls to execute, or (when empty) a final text answer."""

    text: str | None
    tool_calls: list[ToolCall] = field(default_factory=list)


class Conversation(Protocol):
    def send_user(self, text: str) -> LlmTurn: ...

    def send_tool_results(self, results: Sequence[ToolResult]) -> LlmTurn: ...


class LlmProvider(Protocol):
    def conversation(self, system_prompt: str, tools: Sequence[ToolSpec]) -> Conversation: ...


class MissingApiKeyError(RuntimeError):
    pass


class GeminiProvider:
    """Gemini via the google-genai SDK, manual function calling (no auto-execution)."""

    def __init__(self, api_key: str | None = None, model: str | None = None) -> None:
        key = api_key or os.getenv("GEMINI_API_KEY")
        if not key:
            raise MissingApiKeyError(
                "GEMINI_API_KEY is not set; add it to your .env or environment"
            )
        self._client = genai.Client(api_key=key)
        self._model = model or os.getenv("GEMINI_MODEL", DEFAULT_MODEL)

    def conversation(self, system_prompt: str, tools: Sequence[ToolSpec]) -> Conversation:
        declarations = [
            types.FunctionDeclaration(
                name=spec.name,
                description=spec.description,
                parameters=spec.parameters,
            )
            for spec in tools
        ]
        config = types.GenerateContentConfig(
            system_instruction=system_prompt,
            tools=[types.Tool(function_declarations=declarations)],
            temperature=0.2,
        )
        chat = self._client.chats.create(model=self._model, config=config)
        return _GeminiConversation(chat)


class _GeminiConversation:
    def __init__(self, chat: Any) -> None:
        self._chat = chat

    def send_user(self, text: str) -> LlmTurn:
        return _to_turn(self._chat.send_message(text))

    def send_tool_results(self, results: Sequence[ToolResult]) -> LlmTurn:
        parts = [
            types.Part.from_function_response(name=r.name, response={"result": r.payload})
            for r in results
        ]
        return _to_turn(self._chat.send_message(parts))


def _to_turn(response: types.GenerateContentResponse) -> LlmTurn:
    calls = [
        ToolCall(name=fc.name or "", args=dict(fc.args) if fc.args else {})
        for fc in (response.function_calls or [])
    ]
    return LlmTurn(text=response.text, tool_calls=calls)
