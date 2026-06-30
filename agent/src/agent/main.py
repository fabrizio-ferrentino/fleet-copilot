"""FastAPI entrypoint: POST /ask runs the agentic loop.

Run with: uvicorn agent.main:create_app --factory --port 8000
The factory builds the Gemini provider at startup so a missing API key fails
fast with a clear message instead of failing on the first question.
"""

from __future__ import annotations

import json
import os
from collections.abc import Iterator
from typing import Any

from dotenv import find_dotenv, load_dotenv
from fastapi import FastAPI, HTTPException
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import StreamingResponse
from pydantic import BaseModel, Field

from agent.llm import GeminiProvider, LlmError
from agent.loop import AgentAnswer, AgentLoop, AnswerEvent, ErrorEvent, ToolEvent
from agent.tools import DEFAULT_PLATFORM_URL, PlatformTools


class AskRequest(BaseModel):
    question: str = Field(min_length=1, max_length=2000)


class TraceEntry(BaseModel):
    tool: str
    args: dict[str, Any]
    resultSummary: str  # noqa: N815 — camelCase is the wire contract from the spec


class AskResponse(BaseModel):
    answer: str
    toolTrace: list[TraceEntry]  # noqa: N815


def create_app(loop: AgentLoop | None = None) -> FastAPI:
    load_dotenv(find_dotenv(usecwd=True))
    if loop is None:
        provider = GeminiProvider()
        tools = PlatformTools(os.getenv("PLATFORM_URL", DEFAULT_PLATFORM_URL))
        loop = AgentLoop(provider, tools)

    app = FastAPI(title="Fleet Copilot Agent")
    # Local-only project (see non-goals: no auth); the chat UI runs on another port.
    app.add_middleware(
        CORSMiddleware, allow_origins=["*"], allow_methods=["*"], allow_headers=["*"]
    )

    @app.post("/ask")
    def ask(request: AskRequest) -> AskResponse:
        try:
            return _to_response(loop.ask(request.question))
        except LlmError as exc:  # pragma: no cover - ask() maps these to an answer; defensive
            raise HTTPException(status_code=503, detail=exc.user_message) from exc
        except Exception as exc:  # never leak a raw 500
            raise HTTPException(
                status_code=503, detail="The agent failed to answer. Please retry."
            ) from exc

    @app.post("/ask/stream")
    def ask_stream(request: AskRequest) -> StreamingResponse:
        """Server-Sent Events: each tool call streams as it happens, then the final answer."""

        def events() -> Iterator[str]:
            for event in loop.ask_events(request.question):
                if isinstance(event, ToolEvent):
                    yield _sse(
                        "tool",
                        {
                            "tool": event.tool,
                            "args": event.args,
                            "resultSummary": event.result_summary,
                        },
                    )
                elif isinstance(event, AnswerEvent):
                    yield _sse("answer", {"text": event.text})
                elif isinstance(event, ErrorEvent):
                    yield _sse("error", {"message": event.message, "retryable": event.retryable})
            yield _sse("done", {})

        return StreamingResponse(
            events(),
            media_type="text/event-stream",
            headers={"Cache-Control": "no-cache", "X-Accel-Buffering": "no"},
        )

    return app


def _sse(event_type: str, payload: dict[str, Any]) -> str:
    return f"event: {event_type}\ndata: {json.dumps(payload)}\n\n"


def _to_response(result: AgentAnswer) -> AskResponse:
    return AskResponse(
        answer=result.answer,
        toolTrace=[
            TraceEntry(tool=e.tool, args=e.args, resultSummary=e.result_summary)
            for e in result.tool_trace
        ],
    )
