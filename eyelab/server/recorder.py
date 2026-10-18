"""
Session recording for tuning on real faces, off unless EYELAB_RECORD_DIR is set. Never enable it on a shared
server: it writes the user's face to disk.

A recording starts when calibration starts and ends when the look test finishes (or after MAX_FRAMES). It is the
frames exactly as the browser sent them plus events.jsonl, one line per frame or control message with the
pipeline clock, so bench/replay.py can feed a fresh pipeline the same frames at the same moments.
"""

from __future__ import annotations

import json
import os
import pathlib
import time

MAX_FRAMES = 3000   # about three and a half minutes at 15 fps


class SessionRecorder:
    def __init__(self, session_id: int, folder: str | None = None):
        folder = folder if folder is not None else os.environ.get("EYELAB_RECORD_DIR")
        self.root = pathlib.Path(folder) if folder else None
        self.session_id = session_id
        self.dir: pathlib.Path | None = None
        self._events = None
        self.frames = 0

    @property
    def enabled(self) -> bool:
        return self.root is not None

    @property
    def recording(self) -> bool:
        return self._events is not None

    def control(self, kind: str, now_ms: int, **extra) -> None:
        """Calibration starts a new recording; every other control message is noted in the one running."""
        if not self.enabled:
            return
        if kind == "calibrate":
            self.stop()
            self.dir = self.root / time.strftime(f"s{self.session_id}-%Y%m%d-%H%M%S")
            self.dir.mkdir(parents=True, exist_ok=True)
            self._events = open(self.dir / "events.jsonl", "w", encoding="utf-8")
            self.frames = 0
        if self.recording:
            self._write({"t": now_ms, "control": kind, **extra})

    def frame(self, jpeg: bytes, now_ms: int, result: dict) -> None:
        if not self.recording:
            return
        self.frames += 1
        name = f"{self.frames:05d}.jpg"
        (self.dir / name).write_bytes(jpeg)
        self._write({"t": now_ms, "frame": name, "look": (result.get("look") or {}).get("name"),
                     "selected": result.get("selected"), "pitch": (result.get("quality") or {}).get("pitch_deg")})
        test = result.get("look_test") or {}
        if test.get("finished"):
            self._write({"t": now_ms, "report": test.get("report")})
            self.stop()
        elif self.frames >= MAX_FRAMES:
            self.stop()

    def stop(self) -> None:
        if self._events is not None:
            self._events.close()
            self._events = None

    def _write(self, record: dict) -> None:
        self._events.write(json.dumps(record) + "\n")
        self._events.flush()
