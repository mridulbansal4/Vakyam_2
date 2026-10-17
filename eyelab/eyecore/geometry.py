"""
Where to cut each eye out of the frame, as one similarity transform for cv2.warpAffine.

CropGeometry is the same maths as EyeCropGeometry.kt on the phone. EyeAnchors replaces the phone's eyelid
contours (ML Kit, Android only) in one of two ways (PipelineConfig.anchor_mode):

- "frozen" (default): each eye centre is expressed relative to the nose tip, in axes given by the eye line and
  scaled by the eye distance, and these offsets are frozen while the user looks straight during calibration.
  A look moves both pupils, and YuNet's eye points with them, the same way, so the eye-line angle and distance do
  not change, and the nose does not move; the crop therefore stays put on the face whatever the look.
- "landmark": crops are centred on YuNet's eye points every frame. They drift with the pupils, but the crop still
  holds the eyelids and brows, which shift round the pupil, so looks remain distinguishable.

On the synthetic faces in tests/ both modes classify every look correctly, even with eye points that follow the
pupils fully; which one is better on real cameras is for the benchmark to show.
"""

from __future__ import annotations

import math
from dataclasses import dataclass

import cv2
import numpy as np

from .face import Face

CROP_WIDTH, CROP_HEIGHT = 44, 18      # stored calibration crop, as Model.IMAGE_WIDTH/HEIGHT on the phone
LIVE_WIDTH, LIVE_HEIGHT = 52, 22      # live crop at the same scale, 4 px wider and 2 px taller on each side
EYE_CROP_WIDTH = 0.6                  # crop width as a share of the eye distance (the eye is ~0.45 of it)


@dataclass(frozen=True)
class CropGeometry:
    center_x: float
    center_y: float
    roll: float
    scale: float          # output pixels per frame pixel
    out_width: int
    out_height: int

    def forward_matrix(self, origin_x: float = 0.0, origin_y: float = 0.0) -> np.ndarray:
        """2x3 matrix from source pixels (a sub-image whose top-left is at origin in the frame) to crop pixels."""
        c, s = math.cos(self.roll), math.sin(self.roll)
        a, b, cc, d = self.scale * c, self.scale * s, -self.scale * s, self.scale * c
        sx, sy = origin_x - self.center_x, origin_y - self.center_y
        return np.array([[a, b, a * sx + b * sy + self.out_width / 2.0],
                         [cc, d, cc * sx + d * sy + self.out_height / 2.0]], dtype=np.float64)

    def to_frame(self, x: float, y: float) -> tuple[float, float]:
        c, s = math.cos(self.roll), math.sin(self.roll)
        dx, dy = (x - self.out_width / 2.0) / self.scale, (y - self.out_height / 2.0) / self.scale
        return self.center_x + c * dx - s * dy, self.center_y + s * dx + c * dy

    def to_crop(self, x: float, y: float) -> tuple[float, float]:
        m = self.forward_matrix()
        return m[0, 0] * x + m[0, 1] * y + m[0, 2], m[1, 0] * x + m[1, 1] * y + m[1, 2]

    def warp(self, source: np.ndarray, origin_x: int = 0, origin_y: int = 0) -> np.ndarray:
        return cv2.warpAffine(source, self.forward_matrix(origin_x, origin_y), (self.out_width, self.out_height),
                              flags=cv2.INTER_LINEAR, borderMode=cv2.BORDER_REPLICATE)


def face_axes(face: Face) -> tuple[np.ndarray, np.ndarray, float]:
    """Unit vectors along and across the eye line, and the eye distance."""
    c, s = math.cos(face.roll), math.sin(face.roll)
    return np.array([c, s]), np.array([-s, c]), face.eye_distance


@dataclass(frozen=True)
class EyeAnchors:
    """Each eye centre relative to the nose tip, in eye-distance units along and across the eye line."""

    left: tuple[float, float]
    right: tuple[float, float]

    @classmethod
    def from_face(cls, face: Face) -> "EyeAnchors":
        ex, ey, dist = face_axes(face)
        rel = [(face.left_eye - face.nose) / dist, (face.right_eye - face.nose) / dist]
        return cls(*[(float(r @ ex), float(r @ ey)) for r in rel])

    @classmethod
    def mean(cls, anchors: list["EyeAnchors"]) -> "EyeAnchors":
        left = np.mean([a.left for a in anchors], axis=0)
        right = np.mean([a.right for a in anchors], axis=0)
        return cls((float(left[0]), float(left[1])), (float(right[0]), float(right[1])))

    def centers(self, face: Face) -> tuple[np.ndarray, np.ndarray]:
        ex, ey, dist = face_axes(face)
        return tuple(face.nose + dist * (o[0] * ex + o[1] * ey) for o in (self.left, self.right))

    def to_json(self) -> dict:
        return {"left": [round(v, 4) for v in self.left], "right": [round(v, 4) for v in self.right]}


def eye_geometry(center: np.ndarray, face: Face, out_width: int = LIVE_WIDTH,
                 out_height: int = LIVE_HEIGHT) -> CropGeometry | None:
    """Crop round an eye centre, rotated by the head's roll and scaled so CROP_WIDTH spans EYE_CROP_WIDTH eye distances."""
    dist = face.eye_distance
    if dist < 8 or abs(face.roll) > math.radians(45):
        return None
    return CropGeometry(float(center[0]), float(center[1]), face.roll, CROP_WIDTH / (EYE_CROP_WIDTH * dist),
                        out_width, out_height)


def center_of_live(live: np.ndarray) -> np.ndarray:
    """The stored-size crop at the centre of a live crop: both share one scale and centre."""
    x0, y0 = (LIVE_WIDTH - CROP_WIDTH) // 2, (LIVE_HEIGHT - CROP_HEIGHT) // 2
    return live[y0:y0 + CROP_HEIGHT, x0:x0 + CROP_WIDTH].copy()
