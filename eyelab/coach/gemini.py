"""
The Gemini policy: Gemini decides which tool to call next from the OpenCV measurements.

A manual function-calling loop rather than the SDK's automatic one, because the tools are async, drive a live camera
session, and must run through the same CoachRunner as the rule fallback. The model's content is appended back exactly
as returned (it carries Gemini's thought signatures), so the conversation stays append-only.
"""

from __future__ import annotations

import asyncio
from typing import Callable

from server.llm import blocked, failure_kind, finish_reason, first_content

from .runner import PolicyFailed
from .tools import TOOLS

# The same tools, declared for Gemini: TOOLS stays the single definition every policy shares
FUNCTIONS = [{"name": t["name"], "description": t["description"], "parameters_json_schema": t["input_schema"]}
             for t in TOOLS]

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


class GeminiPolicy:
    name = "gemini"

    def __init__(self, client_factory: Callable, model: str, effort: str = "medium", max_tokens: int = 8000):
        self.client_factory = client_factory  # builds a client with its own timeout; raises when there is no key
        self.model = model
        self.effort = effort  # Gemini's thinking level: low, medium or high
        self.max_tokens = max_tokens

    def _config(self) -> dict:
        return {
            "system_instruction": SYSTEM_PROMPT,
            "tools": [{"function_declarations": FUNCTIONS}],
            "automatic_function_calling": {"disable": True},
            "thinking_config": {"thinking_level": self.effort},
            "max_output_tokens": self.max_tokens,  # thinking counts towards it
        }

    async def run(self, coach) -> None:
        try:
            client = self.client_factory()
        except Exception as e:  # noqa: BLE001 - e.g. no Gemini API key: the rule policy takes over
            raise PolicyFailed(f"client unavailable: {type(e).__name__}") from e
        contents: list = [{"role": "user", "parts": [{"text": session_context(coach.env.sensitivity)}]}]
        config = self._config()
        nudged = False
        while coach.finished is None:
            try:
                response = await asyncio.to_thread(
                    client.models.generate_content, model=self.model, contents=contents, config=config)
            except Exception as e:  # noqa: BLE001
                raise PolicyFailed(failure_kind(e, "model ")) from e
            if blocked(response):
                raise PolicyFailed("blocked")
            content = first_content(response)
            if content is None:
                raise PolicyFailed(f"no answer ({finish_reason(response)})")
            contents.append(content)
            parts = getattr(content, "parts", None) or []
            calls = [p.function_call for p in parts if getattr(p, "function_call", None)]
            if not calls:
                if finish_reason(response) == "MAX_TOKENS" or nudged:
                    raise PolicyFailed(f"stopped without finishing ({finish_reason(response)})")
                nudged = True
                contents.append({"role": "user", "parts": [{"text": "Continue with a tool call, or call finish."}]})
                continue
            rationale = " ".join(p.text.strip() for p in parts
                                 if getattr(p, "text", None) and p.text.strip() and not getattr(p, "thought", False))
            results = []
            for call in calls:
                result = await coach.call(call.name, call.args if isinstance(call.args, dict) else {}, rationale)
                response_part = {"name": call.name, "response": result.content}  # errors carry an "error" key
                if getattr(call, "id", None):
                    response_part["id"] = call.id
                results.append({"function_response": response_part})
            contents.append({"role": "user", "parts": results})
