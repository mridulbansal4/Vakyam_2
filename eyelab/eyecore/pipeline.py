"""One session's eye pipeline: a BGR frame in, a JSON-ready result out."""

from __future__ import annotations

import base64
import time
from dataclasses import dataclass

import cv2
import numpy as np

from .calibration import Calibrator
from .dwell import UNRELIABLE, LookDwellSelector
from .face import Face, YuNetDetector
from .geometry import EyeAnchors, center_of_live, eye_geometry
from .looks import Look
from .looktest import LookTest
from .matching import classify
from .quality import FaceQualityGate, QualityIssue, measure

CLAHE_CLIP_LIMIT = 2.0   # Model.CLAHE_CLIP_LIMIT
CLAHE_TILES = 4          # Model.CLAHE_TILES
CLAHE_GROW = 0.15        # the CLAHE region is the face box grown by this much on each side
PITCH_SMOOTHING = 0.25   # weight of each new frame in the smoothed head pitch (solvePnP on 5 points is jumpy)
HEAD_MOVED_CLEAR = 0.7   # HEAD_MOVED clears once the tilt is back under this fraction of its limit


@dataclass
class PipelineConfig:
    sensitivity: int = 20             # same scale as the phone's setting; 20 accepts a correlation of 0.6 per eye
    anchor_mode: str = "frozen"       # "frozen" nose-relative anchors, or "landmark" (YuNet eye points); see geometry
    debug_crops_every: int = 0        # attach PNG eye crops every N frames (0 = never)
    rest_bias: float = 0.0            # see matching.classify; 0 = rank looks as the phone does
    head_moved_deg: float = 10.0      # head pitch change since calibrating that stops input (0 = never)


class EyePipeline:
    def __init__(self, detector: YuNetDetector, config: PipelineConfig | None = None):
        self.detector = detector
        self.config = config or PipelineConfig()
        if self.config.anchor_mode not in ("frozen", "landmark"):
            raise ValueError(f"anchor_mode must be 'frozen' or 'landmark', not {self.config.anchor_mode!r}")
        self.gate = FaceQualityGate()
        self.dwell = LookDwellSelector()
        self.calibrator = Calibrator()
        self._clahe = cv2.createCLAHE(CLAHE_CLIP_LIMIT, (CLAHE_TILES, CLAHE_TILES))
        self._cores = None
        self._frames = 0
        self.pitch: float | None = None                 # smoothed head pitch, degrees
        self._head_moved = False
        self.look_test = LookTest()
        self.last_face: Face | None = None              # for troubleshooting snapshots only
        self.last_live: tuple | None = None
        self.last_eye_centers: list | None = None

    @property
    def calibrated(self) -> bool:
        return self.calibrator.calibration.complete

    def start_calibration(self, now_ms: int) -> dict:
        self.look_test.cancel()
        self.dwell.reset()
        self._cores = None
        return self.calibrator.start(now_ms).to_json()

    def start_look_test(self, now_ms: int, order: tuple[Look, ...] | None = None) -> dict:
        if not self.calibrated:
            raise ValueError("calibrate before the look test")
        self.dwell.reset()
        return self.look_test.start(now_ms, order)

    def head_tilt(self) -> float | None:
        """Smoothed head pitch minus the pitch at calibration, in degrees, when both are known."""
        reference = self.calibrator.calibration.pitch_deg
        return None if self.pitch is None or reference is None else self.pitch - reference

    def _head_moved_issue(self) -> set[QualityIssue]:
        limit = self.config.head_moved_deg
        tilt = self.head_tilt()
        if not limit or tilt is None or self.calibrator.running:
            self._head_moved = False
        else:
            self._head_moved = abs(tilt) > (limit * HEAD_MOVED_CLEAR if self._head_moved else limit)
        return {QualityIssue.HEAD_MOVED} if self._head_moved else set()

    def recalibrate_look(self, look: Look, now_ms: int, prompt: str | None = None) -> dict:
        """Re-record one look; the other templates and the frozen anchors are kept."""
        self.dwell.reset()
        return self.calibrator.start_single(look, now_ms, prompt).to_json()

    def eye_crops(self, bgr: np.ndarray, face: Face, gray: np.ndarray) -> tuple[np.ndarray, np.ndarray] | None:
        """Live (52x22) CLAHE-normalised crops of the image-left and image-right eye, or None."""
        frozen = self.calibrator.calibration.anchors if self.config.anchor_mode == "frozen" else None
        anchors = frozen or EyeAnchors.from_face(face)
        x, y, w, h = face.box
        gx, gy = round(w * CLAHE_GROW), round(h * CLAHE_GROW)
        x0, y0 = max(0, x - gx), max(0, y - gy)
        x1, y1 = min(gray.shape[1], x + w + gx), min(gray.shape[0], y + h + gy)
        if x1 - x0 < 32 or y1 - y0 < 32:
            return None
        region = self._clahe.apply(gray[y0:y1, x0:x1])
        crops = []
        self.last_eye_centers = [tuple(c) for c in anchors.centers(face)]
        for center in anchors.centers(face):
            geometry = eye_geometry(center, face)
            if geometry is None:
                return None
            crops.append(geometry.warp(region, x0, y0))
        return crops[0], crops[1]

    def process(self, bgr: np.ndarray, now_ms: int | None = None) -> dict:
        now_ms = int(time.monotonic() * 1000) if now_ms is None else now_ms
        started = time.perf_counter()
        self._frames += 1

        face = self.detector.detect(bgr)
        self.last_face = face
        gray = cv2.cvtColor(bgr, cv2.COLOR_BGR2GRAY)
        quality = measure(gray, face) if face is not None else None
        if quality is not None and quality.pitch_deg == quality.pitch_deg:   # not NaN
            self.pitch = quality.pitch_deg if self.pitch is None else \
                self.pitch + PITCH_SMOOTHING * (quality.pitch_deg - self.pitch)
        self.gate.update(quality, now_ms, self._head_moved_issue())
        live = self.eye_crops(bgr, face, gray) if face is not None else None
        self.last_live = live

        result: dict = {
            "face": face.to_json() if face is not None else None,
            "quality": quality.to_json() if quality is not None else None,
            "issues": sorted(i.name for i in self.gate.active),
            "issue": self.gate.primary.name if self.gate.primary else None,
            "issue_text": self.gate.primary.message if self.gate.primary else None,
            "calibrated": self.calibrated,
            "head_tilt_deg": None if self.head_tilt() is None else round(self.head_tilt(), 1),
            "look": None,
            "selected": None,
        }

        if self.calibrator.running:
            stored = (center_of_live(live[0]), center_of_live(live[1])) if live is not None else None
            anchors = EyeAnchors.from_face(face) if face is not None else None
            event = self.calibrator.update(stored, anchors, self.gate.current, self.gate.active, now_ms)
            if event.captured == Look.STRAIGHT:
                self.calibrator.calibration.pitch_deg = self.pitch
            result["calibration"] = event.to_json()
            if event.finished:
                self._cores = self.calibrator.calibration.cores()
                result["calibrated"] = True
        elif self.calibrated:
            if self._cores is None:
                self._cores = self.calibrator.calibration.cores()
            observed = UNRELIABLE
            if live is not None and not self.gate.blocks_input:
                match = classify(live[0], live[1], self._cores, self.config.sensitivity, self.config.rest_bias)
                if match.ok and match.look is not None:
                    observed = match.look
                result["look"] = {
                    "tag": match.look, "name": Look(match.look).label if match.look is not None else None,
                    "ok": match.ok, "error": round(match.error, 4), "eyes": match.eyes,
                    "errors": {Look(k).label: round(v, 4) for k, v in match.errors.items()},
                }
            elif self.gate.blocks_input:
                self.dwell.cancel()
            selected = self.dwell.update(observed, now_ms)
            result["holding"] = {"look": Look(self.dwell.candidate).label if self.dwell.candidate > 0 else None,
                                 "progress": round(self.dwell.progress(now_ms), 3)}
            if selected:
                result["selected"] = Look(selected).label
            if self.look_test.running:   # the test scores selections; nothing is typed
                result["look_test"] = self.look_test.update(result["look"], result["selected"], self.pitch, now_ms)
                result["selected"] = None

        every = self.config.debug_crops_every
        if every and live is not None and self._frames % every == 0:
            result["crops"] = [_png_data_url(c) for c in live]
        result["ms"] = round((time.perf_counter() - started) * 1000, 1)
        return result


def _png_data_url(gray: np.ndarray) -> str:
    ok, png = cv2.imencode(".png", gray)
    return "data:image/png;base64," + base64.b64encode(png.tobytes()).decode() if ok else ""
