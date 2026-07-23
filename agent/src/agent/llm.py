"""Provider-agnostic LLM interface and the Gemini implementation.

The agentic loop only ever sees the neutral types below (ToolSpec, LlmTurn,
Conversation, ...), so swapping the provider means writing one new class here.
Per the project non-goals, Gemini is the only implemented provider.
"""

from __future__ import annotations

import os
import time
from collections.abc import Callable, Sequence
from dataclasses import dataclass, field
from typing import Any, Protocol

from google import genai
from google.genai import errors, types

DEFAULT_MODEL = "gemini-2.5-flash"

RETRY_ATTEMPTS = 3
RETRY_BASE_DELAY_S = 1.0


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


class LlmError(RuntimeError):
    """The LLM provider could not answer; carries a user-facing message and
    whether a retry may help.
    """

    def __init__(self, user_message: str, *, retryable: bool) -> None:
        super().__init__(user_message)
        self.user_message = user_message
        self.retryable = retryable


def _send_with_retry(call: Callable[[], Any]) -> Any:
    """Run a provider call, retrying rate limits / transient failures with exponential backoff.

    Any failure is mapped to ``LlmError`` so the agentic loop never propagates a raw
    provider exception (which would surface as an HTTP 500 / "Failed to fetch").
    """
    delay = RETRY_BASE_DELAY_S
    last_exc: Exception | None = None
    for attempt in range(RETRY_ATTEMPTS):
        try:
            return call()
        except errors.ClientError as exc:  # 4xx
            if getattr(exc, "code", None) != 429:
                raise LlmError(
                    "The AI provider rejected the request.", retryable=False
                ) from exc
            last_exc = exc  # 429 RESOURCE_EXHAUSTED — worth retrying
        except errors.ServerError as exc:  # 5xx
            last_exc = exc
        except Exception as exc:  # network / unexpected
            last_exc = exc
        if attempt < RETRY_ATTEMPTS - 1:
            time.sleep(delay)
            delay *= 2
    raise LlmError(
        "The AI provider is temporarily unavailable (rate limit or network). Please retry shortly.",
        retryable=True,
    ) from last_exc


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
        return _to_turn(_send_with_retry(lambda: self._chat.send_message(text)))

    def send_tool_results(self, results: Sequence[ToolResult]) -> LlmTurn:
        parts = [
            types.Part.from_function_response(name=r.name, response={"result": r.payload})
            for r in results
        ]
        return _to_turn(_send_with_retry(lambda: self._chat.send_message(parts)))


def _to_turn(response: types.GenerateContentResponse) -> LlmTurn:
    calls = [
        ToolCall(name=fc.name or "", args=dict(fc.args) if fc.args else {})
        for fc in (response.function_calls or [])
    ]
    return LlmTurn(text=response.text, tool_calls=calls)
