"""YuNet face detection (cv2.FaceDetectorYN), as in YuNetFaceDetector.java."""

from __future__ import annotations

import math
from dataclasses import dataclass

import cv2
import numpy as np

DETECT_LONG_SIDE = 320
SCORE_THRESHOLD = 0.6
NMS_THRESHOLD = 0.3
TOP_K = 5


@dataclass(frozen=True)
class Face:
    """One face in full-frame coordinates. Eyes and mouth corners are named by side in the image."""

    x: float
    y: float
    width: float
    height: float
    score: float
    landmarks: np.ndarray  # (5, 2): image-left eye, image-right eye, nose tip, image-left mouth, image-right mouth

    @property
    def left_eye(self) -> np.ndarray:
        return self.landmarks[0]

    @property
    def right_eye(self) -> np.ndarray:
        return self.landmarks[1]

    @property
    def nose(self) -> np.ndarray:
        return self.landmarks[2]

    @property
    def roll(self) -> float:
        """Angle of the line from the image-left eye to the image-right eye, radians (image y points down)."""
        d = self.right_eye - self.left_eye
        return math.atan2(float(d[1]), float(d[0]))

    @property
    def eye_distance(self) -> float:
        return float(np.linalg.norm(self.right_eye - self.left_eye))

    @property
    def box(self) -> tuple[int, int, int, int]:
        return int(round(self.x)), int(round(self.y)), int(round(self.width)), int(round(self.height))

    def to_json(self) -> dict:
        return {
            "box": [round(self.x, 1), round(self.y, 1), round(self.width, 1), round(self.height, 1)],
            "score": round(self.score, 3),
            "landmarks": np.round(self.landmarks, 1).tolist(),
        }


class YuNetDetector:
    """The largest face in a BGR frame. The frame is shrunk to DETECT_LONG_SIDE first, which keeps YuNet fast."""

    def __init__(self, model_path: str):
        self._detector = cv2.FaceDetectorYN.create(str(model_path), "", (DETECT_LONG_SIDE, DETECT_LONG_SIDE),
                                                   SCORE_THRESHOLD, NMS_THRESHOLD, TOP_K)
        self._input_size: tuple[int, int] | None = None

    def detect(self, bgr: np.ndarray) -> Face | None:
        if bgr is None or bgr.size == 0:
            return None
        h, w = bgr.shape[:2]
        scale = min(1.0, DETECT_LONG_SIDE / max(w, h))
        small = bgr if scale == 1.0 else cv2.resize(bgr, (round(w * scale), round(h * scale)),
                                                    interpolation=cv2.INTER_AREA)
        size = (small.shape[1], small.shape[0])
        if size != self._input_size:
            self._detector.setInputSize(size)
            self._input_size = size
        _, faces = self._detector.detect(small)
        if faces is None or len(faces) == 0:
            return None
        row = max(faces, key=lambda f: f[2] * f[3])
        inv = 1.0 / scale
        lm = (row[4:14].reshape(5, 2) * inv).astype(np.float64)
        if lm[0, 0] > lm[1, 0]:
            lm[[0, 1]] = lm[[1, 0]]
        if lm[3, 0] > lm[4, 0]:
            lm[[3, 4]] = lm[[4, 3]]
        return Face(float(row[0] * inv), float(row[1] * inv), float(row[2] * inv), float(row[3] * inv),
                    float(row[14]), lm)
