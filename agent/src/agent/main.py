"""FastAPI entrypoint: POST /ask runs the agentic loop.

Run with: uvicorn agent.main:create_app --factory --port 8000
The factory builds the Gemini provider at startup so a missing API key fails
fast with a clear message instead of failing on the first question.
"""

from __future__ import annotations

import os
from typing import Any

from dotenv import find_dotenv, load_dotenv
from fastapi import FastAPI
from fastapi.middleware.cors import CORSMiddleware
from pydantic import BaseModel, Field

from agent.llm import GeminiProvider
from agent.loop import AgentAnswer, AgentLoop
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
        return _to_response(loop.ask(request.question))

    return app


def _to_response(result: AgentAnswer) -> AskResponse:
    return AskResponse(
        answer=result.answer,
        toolTrace=[
            TraceEntry(tool=e.tool, args=e.args, resultSummary=e.result_summary)
            for e in result.tool_trace
        ],
    )
