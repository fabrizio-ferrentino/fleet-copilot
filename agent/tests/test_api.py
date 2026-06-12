"""POST /ask contract: wire format per the spec (answer + toolTrace with resultSummary)."""

from __future__ import annotations

from fastapi.testclient import TestClient

from agent.loop import AgentAnswer, TraceEntry
from agent.main import create_app


class StubLoop:
    def ask(self, question: str) -> AgentAnswer:
        return AgentAnswer(
            answer=f"echo: {question}",
            tool_trace=[TraceEntry("get_fleet_status", {}, '{"total":3}')],
        )


client = TestClient(create_app(loop=StubLoop()))  # type: ignore[arg-type]


def test_ask_returns_answer_and_camel_case_trace():
    response = client.post("/ask", json={"question": "How is the fleet?"})

    assert response.status_code == 200
    body = response.json()
    assert body["answer"] == "echo: How is the fleet?"
    assert body["toolTrace"] == [
        {"tool": "get_fleet_status", "args": {}, "resultSummary": '{"total":3}'}
    ]


def test_empty_question_is_rejected():
    assert client.post("/ask", json={"question": ""}).status_code == 422
    assert client.post("/ask", json={}).status_code == 422
