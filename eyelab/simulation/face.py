"""
A drawn face whose eyes can look in each calibrated direction, for testing the pipeline without a camera.

The landmarks it reports follow the pupils by `pupil_follow` (YuNet's eye points do too), so a pipeline that
anchored its crops on them would see every look as the same image; one that uses frozen nose-relative anchors
does not.
"""

from __future__ import annotations

import math

import cv2
import numpy as np

from eyecore.face import Face
from eyecore.looks import Look

FRAME_W, FRAME_H = 640, 480
EYE_DISTANCE = 84.0
IRIS_OFFSETS = {  # pupil offset in pixels for each look (image axes)
    Look.STRAIGHT: (0, 0), Look.LEFT: (-9, 0), Look.RIGHT: (9, 0), Look.UP: (0, -4),
    Look.LEFT_UP: (-8, -4), Look.RIGHT_UP: (8, -4), Look.CLOSED: (0, 0),
}


class FakeDetector:
    """Returns whatever face the test last rendered."""

    def __init__(self):
        self.face: Face | None = None

    def detect(self, bgr):
        return self.face


def render(look: Look, roll_deg: float = 0.0, shift=(0.0, 0.0), pupil_follow: float = 0.6,
           noise: float = 3.0, seed: int = 0, scale: float = 1.0, brightness: float = 1.0,
           head_turn_px: float = 0.0, iris: tuple[int, int] | None = None) -> tuple[np.ndarray, Face]:
    """scale < 1 moves the face away; brightness < 1 darkens the room; head_turn_px moves the nose landmark
    sideways (what YuNet reports for a turned head); iris overrides the pupil offset for this look."""
    rng = np.random.default_rng(seed)
    cx, cy = FRAME_W / 2, FRAME_H / 2 - 20
    img = np.full((FRAME_H, FRAME_W), 70, np.uint8)
    cv2.ellipse(img, (int(cx), int(cy + 30)), (95, 125), 0, 0, 360, 150, -1)   # face
    eyes = [np.array([cx - EYE_DISTANCE / 2, cy]), np.array([cx + EYE_DISTANCE / 2, cy])]
    dx, dy = iris if iris is not None else IRIS_OFFSETS[look]
    for e in eyes:
        ex, ey = int(e[0]), int(e[1])
        cv2.rectangle(img, (ex - 22, ey - 22), (ex + 22, ey - 17), 60, -1)        # brow
        if look == Look.CLOSED:
            cv2.ellipse(img, (ex, ey), (19, 7), 0, 0, 360, 140, -1)
            cv2.line(img, (ex - 19, ey + 1), (ex + 19, ey + 1), 55, 2)            # closed lid
        else:
            cv2.ellipse(img, (ex, ey), (19, 8), 0, 0, 360, 235, -1)              # white of the eye
            cv2.circle(img, (ex + dx, ey + dy), 7, 60, -1)                        # iris
            cv2.circle(img, (ex + dx, ey + dy), 3, 15, -1)                        # pupil
            cv2.ellipse(img, (ex, ey), (19, 8), 0, 180, 360, 90, 2)              # upper lid
    nose = np.array([cx, cy + 0.6 * EYE_DISTANCE])
    cv2.circle(img, (int(nose[0]), int(nose[1])), 6, 120, -1)
    mouth = [np.array([cx - 28, cy + 95]), np.array([cx + 28, cy + 95])]
    cv2.line(img, tuple(map(int, mouth[0])), tuple(map(int, mouth[1])), 80, 3)

    m = cv2.getRotationMatrix2D((cx, cy + 30), -roll_deg, scale)  # negative: OpenCV rotates counter-clockwise
    m[:, 2] += shift
    img = cv2.warpAffine(img, m, (FRAME_W, FRAME_H), borderMode=cv2.BORDER_REPLICATE)
    if brightness != 1.0:
        img = np.clip(img.astype(np.float32) * brightness, 0, 255).astype(np.uint8)
        noise = max(noise, 3.0 / max(brightness, 0.1) * 0.5)  # a dark sensor is a noisy one
    if noise:
        img = np.clip(img.astype(np.float32) + rng.normal(0, noise, img.shape), 0, 255).astype(np.uint8)

    follow = np.array([dx, dy]) * pupil_follow
    nose = nose + np.array([head_turn_px, 0.0])
    points = np.array([eyes[0] + follow, eyes[1] + follow, nose, mouth[0], mouth[1]])
    points = (m[:, :2] @ points.T).T + m[:, 2]
    box_center = m[:, :2] @ np.array([cx, cy + 30]) + m[:, 2]
    face = Face(float(box_center[0] - 100 * scale), float(box_center[1] - 130 * scale), 200.0 * scale,
                260.0 * scale, 0.92, points)
    return cv2.cvtColor(img, cv2.COLOR_GRAY2BGR), face


def angle(face: Face) -> float:
    return math.degrees(face.roll)
