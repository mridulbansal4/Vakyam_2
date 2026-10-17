"""What the coach can sense and do. The live service and the simulator both implement this."""

from __future__ import annotations

from typing import Protocol

from eyecore.looks import Look

CHOOSABLE = (Look.LEFT, Look.RIGHT, Look.UP, Look.LEFT_UP, Look.RIGHT_UP, Look.CLOSED)
LOOK_NAMES = {
    Look.STRAIGHT: "the centre of the screen", Look.LEFT: "the left edge", Look.RIGHT: "the right edge",
    Look.UP: "the top edge", Look.LEFT_UP: "the top left corner", Look.RIGHT_UP: "the top right corner",
    Look.CLOSED: "eyes closed",
}


class CoachEnvironment(Protocol):
    sensitivity: int

    async def camera(self) -> dict:
        """Watch the camera for about two seconds; diagnostics.camera_report of those frames."""

    async def templates(self) -> dict:
        """diagnostics.template_separability of the current calibration."""

    async def self_test(self, look: Look) -> dict:
        """Ask the user to hold a look, then diagnostics.self_test_report of the frames while they held it."""

    async def instruct(self, message: str, wait_s: float) -> None:
        """Show and speak an instruction to the user, then give them wait_s to act on it."""

    async def recalibrate(self, look: Look, instruction: str) -> dict:
        """Re-record one look's template; {"captured": bool, "status": str}."""

    async def ask_caregiver(self, message: str) -> str:
        """A request only a caregiver can carry out; "done", "cannot" or "no_answer"."""

    async def request_approval(self, change: str, reason: str) -> bool:
        """Ask the caregiver to approve a settings change; False unless approved."""

    async def apply_sensitivity(self, value: int) -> None:
        """Set the match sensitivity (only after approval)."""

    async def report(self, event: dict) -> None:
        """Pass a trace event to the UI and logs."""
