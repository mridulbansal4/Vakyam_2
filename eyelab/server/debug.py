"""
Local troubleshooting aids, both off unless their environment variable is set. Never enable them on a shared server:
EYELAB_DEBUG_DIR writes pictures of the user's face to disk.

    EYELAB_LOG_FRAMES=1        one summary line a second per session (numbers only)
    EYELAB_DEBUG_DIR=<folder>  every few seconds, a PNG of the face box, landmarks and the two eye crops
"""

from __future__ import annotations

import json
import logging
import os
import pathlib
import time

import cv2
import numpy as np

log = logging.getLogger("eyelab.frames")
SNAPSHOT_EVERY_S = 3.0


class FrameDebugger:
    def __init__(self, session_id: int):
        self.session_id = session_id
        self.log_frames = os.environ.get("EYELAB_LOG_FRAMES") == "1"
        folder = os.environ.get("EYELAB_DEBUG_DIR")
        self.folder = pathlib.Path(folder) if folder else None
        if self.folder:
            self.folder.mkdir(parents=True, exist_ok=True)
        self._last_log = 0.0
        self._last_snapshot = 0.0
        self._frames = 0

    @property
    def enabled(self) -> bool:
        return self.log_frames or self.folder is not None

    def frame(self, bgr: np.ndarray, result: dict, pipeline) -> None:
        self._frames += 1
        now = time.monotonic()
        if self.log_frames and now - self._last_log >= 1.0:
            self._last_log = now
            q = result.get("quality") or {}
            cal = result.get("calibration") or {}
            look = result.get("look") or {}
            log.info(json.dumps({
                "session": self.session_id, "frames": self._frames, "ms": result.get("ms"),
                "frame": f"{bgr.shape[1]}x{bgr.shape[0]}",
                "face": bool(result.get("face")), "score": (result.get("face") or {}).get("score"),
                "face_w": q.get("face_width_px"), "luma": q.get("face_luma"), "sharp": q.get("sharpness"),
                "yaw": q.get("yaw_deg"), "pitch": q.get("pitch_deg"), "issues": result.get("issues"),
                "calibration": {k: cal.get(k) for k in ("step", "look", "status", "progress", "captured")} if cal else None,
                "look": {k: look.get(k) for k in ("name", "ok", "error")} if look else None,
                "selected": result.get("selected"),
            }))
        if self.folder is not None and now - self._last_snapshot >= SNAPSHOT_EVERY_S and pipeline.last_face is not None:
            self._last_snapshot = now
            cv2.imwrite(str(self.folder / f"s{self.session_id}-{self._frames:05d}.png"), self._snapshot(bgr, pipeline))

    @staticmethod
    def _snapshot(bgr: np.ndarray, pipeline) -> np.ndarray:
        face = pipeline.last_face
        view = bgr.copy()
        x, y, w, h = face.box
        cv2.rectangle(view, (x, y), (x + w, y + h), (101, 197, 237), 2)
        for i, (px, py) in enumerate(face.landmarks):
            cv2.circle(view, (int(px), int(py)), 3, (0, 0, 255) if i < 2 else (174, 218, 185), -1)
        # Where the eye crops were cut from: their centres, from the (frozen or live) anchors
        for cx, cy in pipeline.last_eye_centers or []:
            cv2.drawMarker(view, (int(cx), int(cy)), (255, 0, 255), cv2.MARKER_CROSS, 14, 2)
        pad = 20
        x0, y0 = max(0, x - pad), max(0, y - pad)
        crop = view[y0:min(view.shape[0], y + h + pad), x0:min(view.shape[1], x + w + pad)]
        scale = 360 / max(1, crop.shape[0])
        crop = cv2.resize(crop, (max(1, int(crop.shape[1] * scale)), 360))
        eyes = []
        for eye in pipeline.last_live or []:
            big = cv2.resize(eye, (eye.shape[1] * 6, eye.shape[0] * 6), interpolation=cv2.INTER_NEAREST)
            eyes.append(cv2.cvtColor(big, cv2.COLOR_GRAY2BGR))
        if eyes:
            panel = np.vstack([np.pad(e, ((4, 4), (4, 4), (0, 0)), constant_values=255) for e in eyes])
            if panel.shape[0] < crop.shape[0]:
                panel = np.pad(panel, ((0, crop.shape[0] - panel.shape[0]), (0, 0), (0, 0)), constant_values=255)
            crop = np.hstack([crop, panel[:crop.shape[0]]])
        return crop
