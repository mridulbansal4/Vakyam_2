"""The Calibration Coach inside a live Eye Lab session: perceptions come from the frames the session is processing."""

from __future__ import annotations

import asyncio
import time
from typing import Awaitable, Callable

from coach.tools import describe_look
from eyecore.diagnostics import camera_report, self_test_report, template_separability
from eyecore.looks import Look
from eyecore.pipeline import EyePipeline

CAMERA_S = 2.0
SETTLE_S = 0.8
SELF_TEST_S = 2.5
RECALIBRATE_TIMEOUT_S = 20.0
ANSWER_TIMEOUT_S = 90.0       # no answer from the caregiver counts as "no"


class SessionCoachEnv:
    def __init__(self, pipeline: EyePipeline, send: Callable[[dict], Awaitable[None]]):
        self.pipeline = pipeline
        self._send = send
        self._frames: list[tuple[float, dict]] = []
        self._pending: tuple[str, asyncio.Future] | None = None

    # ---- fed by the session ----
    def on_frame(self, result: dict) -> None:
        now = time.monotonic()
        self._frames.append((now, result))
        while self._frames and now - self._frames[0][0] > 30:
            self._frames.pop(0)

    def answer(self, kind: str, value) -> bool:
        if self._pending is None or self._pending[0] != kind or self._pending[1].done():
            return False
        self._pending[1].set_result(value)
        return True

    async def _collect(self, seconds: float) -> list[dict]:
        start = time.monotonic()
        await asyncio.sleep(seconds)
        return [r for t, r in self._frames if t >= start]

    async def _ask(self, kind: str, payload: dict, default):
        future = asyncio.get_running_loop().create_future()
        self._pending = (kind, future)
        await self._send({"type": "coach", "event": kind, **payload})
        try:
            return await asyncio.wait_for(future, ANSWER_TIMEOUT_S)
        except asyncio.TimeoutError:
            await self._send({"type": "coach", "event": f"{kind}_timeout"})
            return default
        finally:
            self._pending = None

    # ---- CoachEnvironment ----
    @property
    def sensitivity(self) -> int:
        return self.pipeline.config.sensitivity

    async def camera(self) -> dict:
        return camera_report(await self._collect(CAMERA_S))

    async def templates(self) -> dict:
        return template_separability(self.pipeline.calibrator.calibration)

    async def self_test(self, look: Look) -> dict:
        text = "Rest your eyes on the centre and hold." if look == Look.STRAIGHT else \
            "Close your eyes and keep them closed." if look == Look.CLOSED else \
            f"Look at {describe_look(look)} and hold it there."
        await self._send({"type": "coach", "event": "instruct", "look": look.label, "text": text, "test": True})
        await asyncio.sleep(SETTLE_S)
        frames = await self._collect(SELF_TEST_S)
        await self._send({"type": "coach", "event": "instruct_done"})
        return self_test_report(frames, look)

    async def instruct(self, message: str, wait_s: float) -> None:
        await self._send({"type": "coach", "event": "instruct", "text": message})
        await asyncio.sleep(wait_s)
        await self._send({"type": "coach", "event": "instruct_done"})

    async def recalibrate(self, look: Look, instruction: str) -> dict:
        self.pipeline.recalibrate_look(look, int(time.monotonic() * 1000), instruction)
        await self._send({"type": "coach", "event": "instruct", "look": look.label, "text": instruction,
                          "recalibrating": True})
        start = time.monotonic()
        status = None
        try:
            while time.monotonic() - start < RECALIBRATE_TIMEOUT_S:
                await asyncio.sleep(0.1)
                for t, r in reversed(self._frames):
                    if t < start:
                        break
                    calibration = r.get("calibration") or {}
                    if calibration.get("finished"):
                        return {"captured": True, "status": "captured"}
                    status = status or calibration.get("status")
            self.pipeline.calibrator.cancel()
            return {"captured": False, "status": status or "timeout"}
        finally:
            await self._send({"type": "coach", "event": "instruct_done"})

    async def ask_caregiver(self, message: str) -> str:
        answer = await self._ask("caregiver", {"text": message}, "no_answer")
        if answer == "done":
            await asyncio.sleep(1.0)  # let the camera settle after the change
        return answer if answer in ("done", "cannot") else "no_answer"

    async def request_approval(self, change: str, reason: str) -> bool:
        return bool(await self._ask("approval", {"change": change, "reason": reason}, False))

    async def apply_sensitivity(self, value: int) -> None:
        self.pipeline.config.sensitivity = value

    async def report(self, event: dict) -> None:
        await self._send({"type": "coach", **event})
