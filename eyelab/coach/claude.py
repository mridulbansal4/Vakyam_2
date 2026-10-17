"""
The Claude policy: Claude on Amazon Bedrock decides which tool to call next from the OpenCV measurements.

A manual tool-use loop rather than the SDK's tool runner, because the tools are async, drive a live camera session,
and must run through the same CoachRunner as the rule fallback. The assistant's content is appended back exactly as
returned, so the conversation stays append-only.
"""

from __future__ import annotations

import asyncio
import json
from typing import Callable

from .runner import PolicyFailed
from .tools import TOOLS

SYSTEM_PROMPT = """You are the Calibration Coach in Vakyam, an eye-controlled communication aid for people who cannot \
move or speak (for example people with ALS). The user has just calibrated seven looks with their eyes: straight (the \
resting look), left, right, up, left-up, right-up and closed. Your job is to get every choosable look (all but \
straight) recognised reliably, quickly and gently, or to say honestly that a person needs to help.

How to work:
- Start with inspect_camera. Fix the picture first: instruct_user for what the user can do (face the screen, come \
closer, hold still); ask_caregiver for what they cannot (lighting, screen position).
- Then check_templates. If two looks are closer than the threshold, re-record the one that is not straight with \
recalibrate_look and a specific instruction (e.g. "Look further up, at the very top edge.").
- Then self_test each choosable look. A look passes when recognised_rate is at least 0.7.
- If the right look is consistently the best match (best_match_rate 0.8 or more) but rejected because its \
median_error is above the limit, propose_sensitivity; only the caregiver can approve it. Otherwise re-record that look \
and test it again.
- Never try the same fix more than twice. If something cannot be fixed, call finish with outcome needs_help and a \
short, practical summary for the caregiver. Call finish with outcome ready only when every choosable look has passed \
a self test.

Before each tool call, write one short sentence: what the last measurement showed and why you are taking this step. \
Messages to the user must be short (under 15 words), kind, second person, and make no medical claims."""


def session_context(sensitivity: int) -> str:
    return (f"Coaching session started. Calibration is complete. Current sensitivity: {sensitivity} (accepts a "
            f"per-eye template error up to {sensitivity / 50:.2f}). Choosable looks: left, right, up, left-up, "
            f"right-up, closed.")


class ClaudePolicy:
    name = "claude"

    def __init__(self, client_factory: Callable, model: str, effort: str = "medium", timeout_s: float = 30.0,
                 max_tokens: int = 4000):
        self.client_factory = client_factory
        self.model = model
        self.effort = effort
        self.timeout_s = timeout_s
        self.max_tokens = max_tokens

    async def run(self, coach) -> None:
        try:
            client = self.client_factory().with_options(timeout=self.timeout_s, max_retries=1)
        except Exception as e:  # noqa: BLE001 - e.g. no AWS credentials: the rule policy takes over
            raise PolicyFailed(f"client unavailable: {type(e).__name__}") from e
        messages: list[dict] = [{"role": "user", "content": session_context(coach.env.sensitivity)}]
        nudged = False
        while coach.finished is None:
            try:
                response = await asyncio.to_thread(
                    client.messages.create, model=self.model, max_tokens=self.max_tokens, system=SYSTEM_PROMPT,
                    tools=TOOLS, messages=messages, output_config={"effort": self.effort})
            except Exception as e:  # noqa: BLE001
                raise PolicyFailed(_failure_kind(e)) from e
            if response.stop_reason == "refusal":
                raise PolicyFailed("refusal")
            messages.append({"role": "assistant", "content": response.content})
            tool_uses = [b for b in response.content if b.type == "tool_use"]
            if not tool_uses:
                if response.stop_reason == "max_tokens" or nudged:
                    raise PolicyFailed(f"stopped without finishing ({response.stop_reason})")
                nudged = True
                messages.append({"role": "user", "content": "Continue with a tool call, or call finish."})
                continue
            rationale = " ".join(b.text.strip() for b in response.content if b.type == "text" and b.text.strip())
            results = []
            for block in tool_uses:
                result = await coach.call(block.name, block.input if isinstance(block.input, dict) else {}, rationale)
                results.append({"type": "tool_result", "tool_use_id": block.id,
                                "content": json.dumps(result.content), "is_error": result.is_error})
            messages.append({"role": "user", "content": results})


def _failure_kind(e: Exception) -> str:
    try:
        import anthropic
    except ImportError:
        return "anthropic SDK not installed"
    if isinstance(e, anthropic.APITimeoutError):
        return "model timeout"
    if isinstance(e, anthropic.RateLimitError):
        return "model rate limited"
    if isinstance(e, anthropic.APIStatusError):
        return f"model HTTP {e.status_code}"
    if isinstance(e, anthropic.APIConnectionError):
        return "model connection error"
    return type(e).__name__
