"""Face quality: FaceQualityMeter.java (measurement) and FaceQualityGate.kt (decisions), ported."""

from __future__ import annotations

import math
from dataclasses import asdict, dataclass
from enum import Enum

import cv2
import numpy as np

from .face import Face

SHARPNESS_WIDTH = 128

# Generic face in millimetres, camera axes (x right, y down, z away from the camera), nose tip at the origin.
# Same order as Face.landmarks.
FACE_MODEL = np.array([[-31, -32, 26], [31, -32, 26], [0, 0, 0], [-25, 32, 24], [25, 32, 24]], dtype=np.float64)

NAN = float("nan")


@dataclass(frozen=True)
class FaceQuality:
    face_width_px: float = NAN
    sharpness: float = NAN
    face_luma: float = NAN
    frame_luma: float = NAN
    clipped_fraction: float = NAN
    yaw_deg: float = NAN
    pitch_deg: float = NAN
    roll_deg: float = NAN
    face_score: float = NAN

    def to_json(self) -> dict:
        return {k: (None if isinstance(v, float) and math.isnan(v) else round(v, 3)) for k, v in asdict(self).items()}


class QualityIssue(Enum):
    """In the order a caregiver should fix them. Only TURNED_AWAY blocks live input."""

    TURNED_AWAY = (True, "Turn your face towards the screen.")
    TOO_FAR = (False, "Come a little closer to the camera.")
    TOO_DARK = (False, "Too dark. Add light on your face.")
    BACKLIT = (False, "The light is behind you. Face the light.")
    OVEREXPOSED = (False, "Too bright on your face. Move out of direct light.")
    BLURRY = (False, "The picture is blurred. Hold still.")
    TILTED = (False, "Keep the camera level with your eyes.")

    def __init__(self, blocks_input: bool, message: str):
        self.blocks_input = blocks_input
        self.message = message


MAX_YAW_DEG = 35.0
MAX_PITCH_DEG = 30.0
MIN_FACE_WIDTH_PX = 90.0
MIN_FACE_LUMA = 50.0
BACKLIT_LUMA_GAP = 50.0
BACKLIT_MAX_FACE_LUMA = 100.0
MAX_CLIPPED_FRACTION = 0.12
MIN_SHARPNESS = 150.0


def issues_of(q: FaceQuality) -> set[QualityIssue]:
    """The issues of one frame, with no hold. NaN compares false, so an unmeasured value never raises one."""
    issues = set()
    if abs(q.yaw_deg) > MAX_YAW_DEG:
        issues.add(QualityIssue.TURNED_AWAY)
    if q.face_width_px < MIN_FACE_WIDTH_PX:
        issues.add(QualityIssue.TOO_FAR)
    if q.face_luma < MIN_FACE_LUMA:
        issues.add(QualityIssue.TOO_DARK)
    if q.frame_luma - q.face_luma > BACKLIT_LUMA_GAP and q.face_luma < BACKLIT_MAX_FACE_LUMA:
        issues.add(QualityIssue.BACKLIT)
    if q.clipped_fraction > MAX_CLIPPED_FRACTION:
        issues.add(QualityIssue.OVEREXPOSED)
    if q.sharpness < MIN_SHARPNESS:
        issues.add(QualityIssue.BLURRY)
    if abs(q.pitch_deg) > MAX_PITCH_DEG:
        issues.add(QualityIssue.TILTED)
    return issues


class FaceQualityGate:
    """Holds each issue for hold_ms before reporting it, so one bad frame does not flicker the UI."""

    def __init__(self, hold_ms: int = 400):
        self.hold_ms = hold_ms
        self._first_seen: dict[QualityIssue, int] = {}
        self.current: set[QualityIssue] = set()
        self.active: set[QualityIssue] = set()

    @property
    def blocks_input(self) -> bool:
        return any(i.blocks_input for i in self.active)

    @property
    def ok_for_calibration(self) -> bool:
        return not self.current

    @property
    def primary(self) -> QualityIssue | None:
        order = list(QualityIssue)
        return min(self.active, key=order.index) if self.active else None

    def update(self, quality: FaceQuality | None, now_ms: int) -> set[QualityIssue]:
        self.current = issues_of(quality) if quality is not None else set()
        self._first_seen = {i: t for i, t in self._first_seen.items() if i in self.current}
        for issue in self.current:
            self._first_seen.setdefault(issue, now_ms)
        self.active = {i for i in self.current if now_ms - self._first_seen[i] >= self.hold_ms}
        return self.active

    def reset(self) -> None:
        self._first_seen.clear()
        self.current = set()
        self.active = set()


def euler_degrees(r: np.ndarray) -> tuple[float, float, float]:
    """Yaw (about y), pitch (about x), roll (about z) from a 3x3 rotation matrix; same as HeadPose.eulerDegrees."""
    pitch = math.degrees(math.atan2(-r[1, 2], r[2, 2]))
    yaw = math.degrees(math.asin(max(-1.0, min(1.0, r[0, 2]))))
    roll = math.degrees(math.atan2(-r[0, 1], r[0, 0]))
    return yaw, pitch, roll


def head_pose(landmarks: np.ndarray, frame_width: int, frame_height: int) -> tuple[float, float, float] | None:
    """solvePnP (SQPnP) on the five YuNet landmarks, focal length = frame width. Coarse, not a measurement."""
    k = np.array([[frame_width, 0, frame_width / 2], [0, frame_width, frame_height / 2], [0, 0, 1]], dtype=np.float64)
    ok, rvec, _ = cv2.solvePnP(FACE_MODEL, landmarks.astype(np.float64), k, None, flags=cv2.SOLVEPNP_SQPNP)
    if not ok:
        return None
    return euler_degrees(cv2.Rodrigues(rvec)[0])


def measure(gray: np.ndarray, face: Face) -> FaceQuality | None:
    h, w = gray.shape[:2]
    x, y, fw, fh = face.box
    x0, y0, x1, y1 = max(0, x), max(0, y), min(w, x + fw), min(h, y + fh)
    if x1 - x0 < 16 or y1 - y0 < 16:
        return None
    roi = gray[y0:y1, x0:x1]
    small = cv2.resize(roi, (SHARPNESS_WIDTH, max(1, round(SHARPNESS_WIDTH * (y1 - y0) / (x1 - x0)))),
                       interpolation=cv2.INTER_AREA)
    sharpness = float(cv2.Laplacian(small, cv2.CV_64F, ksize=3).var())
    pose = head_pose(face.landmarks, w, h) or (NAN, NAN, NAN)
    return FaceQuality(
        face_width_px=float(x1 - x0),
        sharpness=sharpness,
        face_luma=float(small.mean()),
        frame_luma=float(gray.mean()),
        clipped_fraction=float(np.count_nonzero(small >= 250) / small.size),
        yaw_deg=pose[0], pitch_deg=pose[1], roll_deg=pose[2],
        face_score=face.score,
    )
