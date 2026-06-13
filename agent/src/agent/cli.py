"""Terminal entrypoint: ask the agent one question, or chat in a small REPL."""

from __future__ import annotations

import argparse
import json
import os
import sys

from dotenv import find_dotenv, load_dotenv

from agent.llm import DEFAULT_MODEL, GeminiProvider, MissingApiKeyError
from agent.loop import AgentAnswer, AgentLoop
from agent.tools import DEFAULT_PLATFORM_URL, PlatformTools


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        prog="fleet-agent",
        description="Ask Fleet Copilot about the fleet; without a question it starts a REPL.",
    )
    parser.add_argument(
        "question", nargs="?", help="one question, e.g. 'Which devices are offline?'"
    )
    parser.add_argument(
        "--platform-url",
        default=os.getenv("PLATFORM_URL", DEFAULT_PLATFORM_URL),
        help=f"platform base URL (env PLATFORM_URL, default {DEFAULT_PLATFORM_URL})",
    )
    parser.add_argument(
        "--model",
        default=None,
        help=f"Gemini model id (env GEMINI_MODEL, default {DEFAULT_MODEL})",
    )
    return parser.parse_args()


def print_answer(result: AgentAnswer) -> None:
    print()
    print(result.answer.strip())
    print()
    print(f"how I investigated ({len(result.tool_trace)} tool calls):")
    if not result.tool_trace:
        print("  (no tools were called)")
    for i, entry in enumerate(result.tool_trace, start=1):
        args = json.dumps(entry.args, separators=(",", ":")) if entry.args else "{}"
        print(f"  {i}. {entry.tool} {args} -> {entry.result_summary}")
    print()


def main() -> None:
    load_dotenv(find_dotenv(usecwd=True))  # pick up GEMINI_API_KEY from the repo .env
    args = parse_args()
    try:
        provider = GeminiProvider(model=args.model)
    except MissingApiKeyError as exc:
        print(f"error: {exc}", file=sys.stderr)
        raise SystemExit(1) from exc
    loop = AgentLoop(provider, PlatformTools(args.platform_url))

    if args.question:
        print_answer(loop.ask(args.question))
        return

    print("Fleet Copilot — ask about the fleet (empty line or 'exit' to quit)")
    while True:
        try:
            question = input("you> ").strip()
        except (EOFError, KeyboardInterrupt):
            print()
            break
        if not question or question.lower() in {"exit", "quit"}:
            break
        print_answer(loop.ask(question))


if __name__ == "__main__":
    main()
