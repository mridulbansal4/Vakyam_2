"""
A simulated session for the Calibration Coach: a drawn face, a simulated user and caregiver, and an injected fault.

The pipeline is the real one (eyecore with OpenCV 5); only the camera, the person in front of it and the caregiver
are simulated. Time is simulated too: a frame is 33 ms, so a run takes seconds, not minutes.

The simulated user and caregiver react to what they are asked by keyword, so a policy is only rewarded when its
instruction actually names the problem.
"""

from __future__ import annotations

import asyncio
from dataclasses import dataclass, field

from eyecore.diagnostics import camera_report, self_test_report, template_separability
from eyecore.looks import CALIBRATION_ORDER, Look
from eyecore.pipeline import EyePipeline, PipelineConfig

from .face import IRIS_OFFSETS, FakeDetector, render

FRAME_MS = 33
SETTLE_MS = 800
SELF_TEST_MS = 2500
CAMERA_MS = 2000

USER_KEYWORDS = {
    "turned": ("turn", "face the", "facing", "towards", "toward", "look at the screen"),
    "far": ("closer", "nearer", "move in", "come in"),
}
CAREGIVER_KEYWORDS = {
    "dark": ("light", "lamp", "brighter", "dark"),
    "hidden": ("screen", "camera", "position", "move", "angle", "face"),
}


@dataclass
class Scenario:
    name: str
    description: str
    expected: str                                   # the outcome a good coach reaches: "ready" or "needs_help"
    weak_calibration: dict = field(default_factory=dict)  # Look -> iris offset the user gave when calibrating
    dark: float = 0.0                               # the room went dark after calibration: 0.25 dim, 0.1 very dark
    far: bool = False                               # the user slumped away from the camera
    turned: bool = False                            # the user turned to look at a visitor
    hidden: bool = False                            # the screen was knocked: the camera no longer sees a face
    sensitivity: int = 20
    noise: float = 3.0
    user_can_fix: tuple = ("turned", "far")
    caregiver_can_fix: bool = True
    caregiver_approves: bool = True


SCENARIOS = [
    Scenario("clean", "Nothing wrong: the coach should confirm and finish quickly.", "ready"),
    Scenario("weak_up", "While calibrating 'up' the user only glanced up a little, so up and straight are look-alikes.",
             "ready", weak_calibration={Look.UP: (0, -2)}),
    Scenario("turned_away", "The user has turned their head well away from the screen.", "ready", turned=True),
    Scenario("too_far", "The user has slumped back, far from the camera.", "ready", far=True),
    Scenario("dark_room", "The room light went off; the caregiver can switch it on.", "ready", dark=0.25),
    Scenario("dim_room_unfixable", "The room is dim and the caregiver cannot add light, but the looks still work: "
             "the coach should test them rather than give up.", "ready", dark=0.25, caregiver_can_fix=False),
    Scenario("very_dark_unfixable", "The room is very dark and nobody can add light. At the set threshold several "
             "looks fail; one approved sensitivity change restores them without misreading the resting look.",
             "ready", dark=0.1, caregiver_can_fix=False),
    Scenario("no_face", "The screen was knocked and the camera no longer sees a face; the caregiver re-aims it.",
             "ready", hidden=True),
    Scenario("no_face_unfixable", "The camera no longer sees a face and nobody can move the screen: the coach must "
             "stop and say so, not loop.", "needs_help", hidden=True, caregiver_can_fix=False),
    Scenario("strict_threshold", "Sensitivity was set too strict for this noisy camera: closed eyes, which have "
             "little texture, are the right match but rejected. The caregiver approves a change.", "ready",
             sensitivity=4, noise=9.0),
    Scenario("strict_threshold_denied", "As above, but the caregiver declines the change.", "needs_help",
             sensitivity=4, noise=9.0, caregiver_approves=False),
]


class SimEnvironment:
    def __init__(self, scenario: Scenario, seed: int = 0):
        self.scenario = scenario
        self.detector = FakeDetector()
        self.pipeline = EyePipeline(self.detector, PipelineConfig(sensitivity=scenario.sensitivity,
                                                                  debug_crops_every=0))
        self.t = 0
        self.seed = seed * 100_000
        self.events: list[dict] = []
        self.dark = 0.0
        self.far = self.turned = self.hidden = False
        self.approvals_asked = 0
        self.caregiver_asks = 0

    # ---- the simulated world ----
    def frame(self, look: Look, iris=None) -> dict:
        self.seed += 1
        self.t += FRAME_MS
        image, self.detector.face = render(
            look, seed=self.seed, noise=self.scenario.noise, iris=iris,
            brightness=self.dark or 1.0, scale=0.4 if self.far else 1.0,
            head_turn_px=40.0 if self.turned else 0.0)
        if self.hidden:
            self.detector.face = None
        return self.pipeline.process(image, self.t)

    def calibrate(self) -> None:
        """The initial calibration, under clean conditions, with the scenario's weak looks."""
        self.pipeline.start_calibration(self.t)
        for look in CALIBRATION_ORDER:
            iris = self.scenario.weak_calibration.get(look)
            for _ in range(600):
                if self.frame(look, iris)["calibration"]["captured"] == look.label:
                    break
            else:
                raise RuntimeError(f"simulated calibration stuck at {look.label}")
        self.dark, self.far, self.turned = self.scenario.dark, self.scenario.far, self.scenario.turned
        self.hidden = self.scenario.hidden

    def ground_truth(self) -> dict:
        """Independent check after coaching, not counted as coaching: does every choosable look work, and is the
        resting look left alone? (A threshold loosened too far turns resting into false selections.)"""
        rates = {look.label: self._hold(look)["recognised_rate"] for look in CALIBRATION_ORDER[1:]}
        resting = self._hold(Look.STRAIGHT)
        misread = 1.0 - resting["recognised_rate"] if resting["frames"] else 1.0
        return {"recognised": rates, "resting_misread_rate": round(misread, 2),
                "ok": min(rates.values()) >= 0.7 and misread <= 0.1}

    def _hold(self, look: Look) -> dict:
        for _ in range(SETTLE_MS // FRAME_MS):
            self.frame(look)
        return self_test_report([self.frame(look) for _ in range(SELF_TEST_MS // FRAME_MS)], look)

    # ---- CoachEnvironment ----
    @property
    def sensitivity(self) -> int:
        return self.pipeline.config.sensitivity

    async def camera(self) -> dict:
        await asyncio.sleep(0)
        return camera_report([self.frame(Look.STRAIGHT) for _ in range(CAMERA_MS // FRAME_MS)])

    async def templates(self) -> dict:
        return template_separability(self.pipeline.calibrator.calibration)

    async def self_test(self, look: Look) -> dict:
        await asyncio.sleep(0)
        return self._hold(look)

    async def instruct(self, message: str, wait_s: float) -> None:
        text = message.lower()
        for fault, words in USER_KEYWORDS.items():
            if getattr(self, fault) and fault in self.scenario.user_can_fix and any(w in text for w in words):
                setattr(self, fault, False)
        for _ in range(int(wait_s * 1000) // FRAME_MS):
            self.frame(Look.STRAIGHT)

    async def recalibrate(self, look: Look, instruction: str) -> dict:
        self.pipeline.recalibrate_look(look, self.t, instruction)
        status = None
        for _ in range(600):
            result = self.frame(look)  # asked specifically, the user now looks all the way
            calibration = result.get("calibration") or {}
            status = calibration.get("status")
            if calibration.get("finished"):
                return {"captured": True, "status": "captured"}
        self.pipeline.calibrator.cancel()
        return {"captured": False, "status": status}

    async def ask_caregiver(self, message: str) -> str:
        self.caregiver_asks += 1
        if not self.scenario.caregiver_can_fix:
            return "cannot"
        text = message.lower()
        for fault, words in CAREGIVER_KEYWORDS.items():
            if getattr(self, fault) and any(w in text for w in words):
                setattr(self, fault, 0.0 if fault == "dark" else False)
        for _ in range(3000 // FRAME_MS):
            self.frame(Look.STRAIGHT)
        return "done"

    async def request_approval(self, change: str, reason: str) -> bool:
        self.approvals_asked += 1
        return self.scenario.caregiver_approves

    async def apply_sensitivity(self, value: int) -> None:
        self.pipeline.config.sensitivity = value

    async def report(self, event: dict) -> None:
        self.events.append(event)


__all__ = ["Scenario", "SCENARIOS", "SimEnvironment", "IRIS_OFFSETS"]
