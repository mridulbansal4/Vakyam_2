"""
The coach's tools: tool definitions (JSON Schema) and one executor shared by every policy.

Inputs are validated here, not trusted: an unknown look, a long message or an out-of-range setting comes back as an
error result for the policy to correct, and a settings change never happens without the caregiver's approval.
"""

from __future__ import annotations

from dataclasses import dataclass

from eyecore.looks import Look

from .environment import CHOOSABLE, LOOK_NAMES, CoachEnvironment

LOOK_LABELS = [look.label for look in Look]
MAX_MESSAGE = 160
SENSITIVITY_RANGE = (10, 45)  # 1 - correlation of 0.2 .. 0.9 per eye

TOOLS = [
    {
        "name": "inspect_camera",
        "description": "Watch the camera for about two seconds and measure, with OpenCV 5, whether a face is found and "
                       "whether the picture is usable: face size, brightness of the face and of the whole frame, "
                       "sharpness (variance of the Laplacian), head yaw and pitch (solvePnP), and the share of frames "
                       "with each quality issue (TURNED_AWAY, TOO_FAR, TOO_DARK, BACKLIT, OVEREXPOSED, BLURRY, TILTED).",
        "input_schema": {"type": "object", "properties": {}, "additionalProperties": False},
    },
    {
        "name": "check_templates",
        "description": "Compare every pair of calibrated looks with matchTemplate and return the closest pairs. "
                       "Distance is 1 - normalised cross-correlation averaged over both eyes; pairs under the "
                       "threshold (0.15) are likely to be confused during use.",
        "input_schema": {"type": "object", "properties": {}, "additionalProperties": False},
    },
    {
        "name": "self_test",
        "description": "Ask the user to look at one target and hold it for about three seconds, and measure how often "
                       "that look is recognised. Returns recognised_rate (matched and accepted), best_match_rate "
                       "(the right look was the closest template, accepted or not), median_error for the target, "
                       "median_margin to the runner-up, and which looks it was confused with.",
        "input_schema": {"type": "object", "additionalProperties": False, "required": ["look"],
                         "properties": {"look": {"type": "string", "enum": LOOK_LABELS}}},
    },
    {
        "name": "instruct_user",
        "description": "Show and speak a short instruction to the user (the person who cannot move their hands; they "
                       "can move their head and eyes a little), wait a few seconds, then inspect the camera again and "
                       "return that report. Use it for things the user can do: face the screen, come closer, hold "
                       "still.",
        "input_schema": {"type": "object", "additionalProperties": False, "required": ["message"],
                         "properties": {"message": {"type": "string", "maxLength": MAX_MESSAGE},
                                        "wait_seconds": {"type": "number", "minimum": 2, "maximum": 8}}},
    },
    {
        "name": "recalibrate_look",
        "description": "Re-record the template for one look; the other templates are kept. Give a specific "
                       "instruction, e.g. 'Look further up, at the very top edge.' Returns whether it was captured "
                       "and the new closest pairs involving that look.",
        "input_schema": {"type": "object", "additionalProperties": False, "required": ["look", "instruction"],
                         "properties": {"look": {"type": "string", "enum": LOOK_LABELS},
                                        "instruction": {"type": "string", "maxLength": MAX_MESSAGE}}},
    },
    {
        "name": "ask_caregiver",
        "description": "Ask the caregiver to do something the user cannot, such as turning on a light, closing a "
                       "curtain or moving the screen. Returns 'done', 'cannot' or 'no_answer', and a fresh camera "
                       "report when the answer is 'done'.",
        "input_schema": {"type": "object", "additionalProperties": False, "required": ["message"],
                         "properties": {"message": {"type": "string", "maxLength": 200}}},
    },
    {
        "name": "propose_sensitivity",
        "description": "Propose a new match sensitivity (10-45; currently shown in the session context). The largest "
                       "accepted per-eye error is sensitivity / 50. Only the caregiver can approve it; nothing changes "
                       "unless they do. Propose it only when self tests show the right look is consistently the best "
                       "match but is rejected because its error is above the threshold.",
        "input_schema": {"type": "object", "additionalProperties": False, "required": ["value", "reason"],
                         "properties": {"value": {"type": "integer", "minimum": SENSITIVITY_RANGE[0],
                                                  "maximum": SENSITIVITY_RANGE[1]},
                                        "reason": {"type": "string", "maxLength": 200}}},
    },
    {
        "name": "finish",
        "description": "End coaching. outcome 'ready' only when self tests show every choosable look is recognised "
                       "reliably; otherwise 'needs_help', with a summary of what is wrong and what a person should do.",
        "input_schema": {"type": "object", "additionalProperties": False, "required": ["outcome", "summary"],
                         "properties": {"outcome": {"type": "string", "enum": ["ready", "needs_help"]},
                                        "summary": {"type": "string", "maxLength": 400}}},
    },
]
TOOL_NAMES = {t["name"] for t in TOOLS}


class ToolInputError(ValueError):
    pass


@dataclass
class ToolResult:
    content: dict
    is_error: bool = False
    finished: bool = False


def _look(value) -> Look:
    for look in Look:
        if look.label == value:
            return look
    raise ToolInputError(f"look must be one of {LOOK_LABELS}, not {value!r}")


def _text(value, limit: int, field: str) -> str:
    if not isinstance(value, str) or not value.strip():
        raise ToolInputError(f"{field} must be a non-empty string")
    value = " ".join(value.split())
    if len(value) > limit:
        raise ToolInputError(f"{field} must be at most {limit} characters")
    return value


async def execute(env: CoachEnvironment, name: str, args: dict) -> ToolResult:
    if name not in TOOL_NAMES:
        return ToolResult({"error": f"unknown tool {name!r}"}, is_error=True)
    if not isinstance(args, dict):
        return ToolResult({"error": "input must be an object"}, is_error=True)
    try:
        if name == "inspect_camera":
            return ToolResult(await env.camera())
        if name == "check_templates":
            return ToolResult(await env.templates())
        if name == "self_test":
            return ToolResult(await env.self_test(_look(args.get("look"))))
        if name == "instruct_user":
            message = _text(args.get("message"), MAX_MESSAGE, "message")
            wait = float(args.get("wait_seconds", 4))
            if not 2 <= wait <= 8:
                raise ToolInputError("wait_seconds must be between 2 and 8")
            await env.instruct(message, wait)
            return ToolResult({"instructed": message, "camera_after": await env.camera()})
        if name == "recalibrate_look":
            look = _look(args.get("look"))
            result = await env.recalibrate(look, _text(args.get("instruction"), MAX_MESSAGE, "instruction"))
            separability = await env.templates()
            result["closest_pairs_with_this_look"] = [
                p for p in separability["closest_pairs"] if look.label in p["looks"]][:3]
            result["threshold"] = separability["threshold"]
            return ToolResult(result)
        if name == "ask_caregiver":
            answer = await env.ask_caregiver(_text(args.get("message"), 200, "message"))
            out = {"answer": answer}
            if answer == "done":
                out["camera_after"] = await env.camera()
            return ToolResult(out)
        if name == "propose_sensitivity":
            value = args.get("value")
            if not isinstance(value, int) or not SENSITIVITY_RANGE[0] <= value <= SENSITIVITY_RANGE[1]:
                raise ToolInputError(f"value must be an integer from {SENSITIVITY_RANGE[0]} to {SENSITIVITY_RANGE[1]}")
            reason = _text(args.get("reason"), 200, "reason")
            before = env.sensitivity
            approved = await env.request_approval(f"Change match sensitivity from {before} to {value}", reason)
            if approved:
                await env.apply_sensitivity(value)
            return ToolResult({"approved": approved, "sensitivity": env.sensitivity})
        if name == "finish":
            outcome = args.get("outcome")
            if outcome not in ("ready", "needs_help"):
                raise ToolInputError("outcome must be 'ready' or 'needs_help'")
            return ToolResult({"outcome": outcome, "summary": _text(args.get("summary"), 400, "summary")},
                              finished=True)
    except ToolInputError as e:
        return ToolResult({"error": str(e)}, is_error=True)
    raise AssertionError(name)


def describe_look(look: Look) -> str:
    return LOOK_NAMES[look]


__all__ = ["TOOLS", "ToolResult", "execute", "CHOOSABLE", "describe_look"]
