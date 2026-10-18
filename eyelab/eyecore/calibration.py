"""
Hands-free calibration: one stored crop per eye per look, captured once the eyes have held still.

The phone does the same with CalibrationStabilityGate (iris position) and CalibrationTemplateCheck (distinct
templates). Here steadiness is judged on the crops themselves with the matcher's own error, which needs no iris
tracker, and the looking-straight step also freezes the eye anchors (geometry.EyeAnchors).
"""

from __future__ import annotations

from dataclasses import dataclass, field
from enum import Enum

import numpy as np

from .geometry import EyeAnchors
from .looks import CALIBRATION_ORDER, CALIBRATION_PROMPTS, Look
from .matching import template_core, template_error
from .quality import QualityIssue

SETTLE_MS = 700             # after the prompt, let the eyes get there
HOLD_MS = 400               # then the crops must stay the same this long
STEADY_ERROR = 0.10         # frame-to-frame error (summed over both eyes) that still counts as still; each
                            # re-prompt loosens it by half again, as the phone's gate loosens, so a noisy camera
                            # (closed eyes have little texture, so noise dominates) cannot stall a step forever
MIN_DISTINCT_ERROR = 0.06   # per eye: a template closer than this to an earlier look cannot be told apart from it
REPROMPT_MS = 5000          # repeat the prompt, and loosen the rules a step, if nothing was captured by then
QUALITY_STRICT_UNTIL = 2    # after this many re-prompts only a blocking quality issue refuses a capture


class Status(Enum):
    IDLE = "Press start to calibrate."
    NO_FACE = "Face not found. Look at the camera."
    SETTLING = "Listen to the instruction…"
    QUALITY = "Fix the picture first."
    NOT_STEADY = "Keep looking in that direction."
    HOLDING = "Hold… capturing."
    LOOK_FURTHER = "Move your eyes further in that direction."
    DONE = "Calibration finished."


@dataclass
class Calibration:
    """Stored crops (44x18, CLAHE-normalised) per look, as (left eye, right eye), and the frozen anchors."""

    crops: dict[int, tuple[np.ndarray, np.ndarray]] = field(default_factory=dict)
    anchors: EyeAnchors | None = None
    pitch_deg: float | None = None    # head pitch when looking straight was captured (set by the pipeline)

    @property
    def complete(self) -> bool:
        return all(int(look) in self.crops for look in CALIBRATION_ORDER) and self.anchors is not None

    def cores(self) -> dict[int, tuple[np.ndarray, np.ndarray]]:
        return {look: (template_core(l), template_core(r)) for look, (l, r) in self.crops.items()}


@dataclass(frozen=True)
class CalibrationEvent:
    """What the UI needs this frame: the step, a status, hold progress, and whether a step was just captured."""

    running: bool
    step: int
    look: Look | None
    prompt: str
    status: Status
    status_text: str
    progress: float
    captured: Look | None = None
    reprompt: bool = False
    finished: bool = False

    def to_json(self) -> dict:
        return {
            "running": self.running, "step": self.step, "steps": len(CALIBRATION_ORDER),
            "look": self.look.label if self.look is not None else None,
            "prompt": self.prompt, "status": self.status.name, "status_text": self.status_text,
            "progress": round(self.progress, 3),
            "captured": self.captured.label if self.captured is not None else None,
            "reprompt": self.reprompt, "finished": self.finished,
        }


class Calibrator:
    def __init__(self):
        self.calibration = Calibration()
        self.running = False
        self.step = 0
        self.leniency = 0
        self._step_start_ms = 0
        self._last_prompt_ms = 0
        self._steady_since: int | None = None
        self._previous: tuple[np.ndarray, np.ndarray] | None = None
        self._anchor_samples: list[EyeAnchors] = []
        self.single: Look | None = None   # re-recording one look, the rest of the calibration kept
        self._prompt_override: str | None = None

    @property
    def look(self) -> Look | None:
        return CALIBRATION_ORDER[self.step] if self.running else None

    def start(self, now_ms: int) -> CalibrationEvent:
        self.calibration = Calibration()
        self.running = True
        self.single = None
        self.step = 0
        self.leniency = 0
        self._begin_step(now_ms)
        return self._event(Status.SETTLING, 0.0, reprompt=True)

    def start_single(self, look: Look, now_ms: int, prompt: str | None = None) -> CalibrationEvent:
        """Re-record one look. The frozen eye anchors are kept, so the other templates stay valid."""
        if not self.calibration.complete:
            raise ValueError("calibrate fully before re-recording one look")
        self.running = True
        self.single = look
        self.step = CALIBRATION_ORDER.index(look)
        self.leniency = 0
        self._prompt_override = prompt
        self._begin_step(now_ms)
        return self._event(Status.SETTLING, 0.0, reprompt=True)

    def cancel(self) -> None:
        self.running = False

    def _begin_step(self, now_ms: int) -> None:
        self._step_start_ms = now_ms
        self._last_prompt_ms = now_ms
        self._steady_since = None
        self._previous = None
        self._anchor_samples = []

    def update(self, crops: tuple[np.ndarray, np.ndarray] | None, anchors: EyeAnchors | None,
               current_issues: set[QualityIssue], held_issues: set[QualityIssue], now_ms: int) -> CalibrationEvent:
        """crops are this frame's stored-size crops (left, right), or None when no face or eyes were found."""
        if not self.running:
            return self._event(Status.DONE if self.calibration.complete else Status.IDLE, 0.0)

        reprompt = False
        if now_ms - self._last_prompt_ms > REPROMPT_MS:
            self._last_prompt_ms = now_ms
            self.leniency += 1
            reprompt = True

        if crops is None:
            self._steady_since, self._previous = None, None
            return self._event(Status.NO_FACE, 0.0, reprompt=reprompt)

        steady = self._previous is not None and sum(
            template_error(c, template_core(p)) for c, p in zip(crops, self._previous)) <             STEADY_ERROR * (1 + 0.5 * self.leniency)
        self._previous = crops
        if now_ms - self._step_start_ms < SETTLE_MS:
            return self._event(Status.SETTLING, 0.0, reprompt=reprompt)

        refusing = current_issues if self.leniency < QUALITY_STRICT_UNTIL else \
            {i for i in current_issues if i.blocks_input}
        if refusing:
            self._steady_since = None
            issue = min(held_issues or refusing, key=list(QualityIssue).index)
            return self._event(Status.QUALITY, 0.0, text=issue.message, reprompt=reprompt)

        if not steady:
            self._steady_since = None
            self._anchor_samples = []
            return self._event(Status.NOT_STEADY, 0.0, reprompt=reprompt)
        if self._steady_since is None:
            self._steady_since = now_ms
        if anchors is not None:
            self._anchor_samples.append(anchors)
        held = now_ms - self._steady_since
        if held < HOLD_MS:
            return self._event(Status.HOLDING, held / HOLD_MS, reprompt=reprompt)

        min_distinct = MIN_DISTINCT_ERROR / (2 if self.leniency >= 3 else 1)
        for look, earlier in self.calibration.crops.items():
            if self.single is not None and look == int(self.single):
                continue  # the template being replaced
            per_eye = [template_error(c, template_core(e)) for c, e in zip(crops, earlier)]
            if sum(per_eye) / 2 < min_distinct:
                self._steady_since = None
                return self._event(Status.LOOK_FURTHER, 0.0, reprompt=reprompt)

        captured = CALIBRATION_ORDER[self.step]
        self.calibration.crops[int(captured)] = (crops[0].copy(), crops[1].copy())
        if self.single is not None:
            self.running = False
            self.single = None
            return CalibrationEvent(False, len(CALIBRATION_ORDER), None, "", Status.DONE, Status.DONE.value, 1.0,
                                    captured=captured, finished=True)
        if captured == Look.STRAIGHT and self._anchor_samples:
            self.calibration.anchors = EyeAnchors.mean(self._anchor_samples)
        self.step += 1
        self.leniency = 0
        if self.step >= len(CALIBRATION_ORDER):
            self.running = False
            return CalibrationEvent(False, len(CALIBRATION_ORDER), None, "", Status.DONE, Status.DONE.value, 1.0,
                                    captured=captured, finished=True)
        self._begin_step(now_ms)
        return self._event(Status.SETTLING, 0.0, captured=captured, reprompt=True)

    def _event(self, status: Status, progress: float, text: str | None = None, captured: Look | None = None,
               reprompt: bool = False) -> CalibrationEvent:
        look = self.look
        prompt = (self._prompt_override if self.single is not None and self._prompt_override
                  else CALIBRATION_PROMPTS[look] if look is not None else "")
        return CalibrationEvent(self.running, self.step, look, prompt,
                                status, text or status.value, progress, captured=captured, reprompt=reprompt)
