"""
Session metrics as CloudWatch Embedded Metric Format: one JSON line on stdout, which the ECS awslogs driver ships
to CloudWatch Logs, where it becomes metrics with no agent or API calls. No images and no sentence text are logged.
"""

from __future__ import annotations

import json
import sys
import time
from dataclasses import dataclass, field

NAMESPACE = "Vakyam/EyeLab"


def emit(metrics: dict[str, tuple[float, str]], properties: dict | None = None, stream=None) -> dict:
    """metrics: name -> (value, CloudWatch unit)."""
    record = {
        "_aws": {
            "Timestamp": int(time.time() * 1000),
            "CloudWatchMetrics": [{
                "Namespace": NAMESPACE,
                "Dimensions": [["Service"]],
                "Metrics": [{"Name": name, "Unit": unit} for name, (_, unit) in metrics.items()],
            }],
        },
        "Service": "eyelab",
        **{name: value for name, (value, _) in metrics.items()},
        **(properties or {}),
    }
    print(json.dumps(record), file=stream or sys.stdout, flush=True)
    return record


@dataclass
class SessionStats:
    started: float = field(default_factory=time.monotonic)
    frames: int = 0
    frame_ms: list[float] = field(default_factory=list)
    faces: int = 0
    selections: int = 0
    quality_frames: int = 0
    calibration_started: float | None = None
    calibration_s: float | None = None
    sentences: int = 0
    sentence_fallbacks: int = 0

    def frame(self, result: dict) -> None:
        self.frames += 1
        self.frame_ms.append(result.get("ms", 0.0))
        self.faces += result.get("face") is not None
        self.selections += result.get("selected") is not None
        self.quality_frames += bool(result.get("issues"))
        calibration = result.get("calibration")
        if calibration and calibration.get("finished") and self.calibration_started is not None:
            self.calibration_s = time.monotonic() - self.calibration_started

    def summary(self, stream=None) -> dict:
        ms = sorted(self.frame_ms) or [0.0]
        metrics = {
            "SessionSeconds": (round(time.monotonic() - self.started, 1), "Seconds"),
            "Frames": (self.frames, "Count"),
            "FrameMsAvg": (round(sum(ms) / len(ms), 2), "Milliseconds"),
            "FrameMsP95": (ms[min(len(ms) - 1, int(len(ms) * 0.95))], "Milliseconds"),
            "FaceFoundRate": (round(self.faces / self.frames, 3) if self.frames else 0.0, "None"),
            "QualityIssueRate": (round(self.quality_frames / self.frames, 3) if self.frames else 0.0, "None"),
            "Selections": (self.selections, "Count"),
            "Sentences": (self.sentences, "Count"),
            "SentenceFallbacks": (self.sentence_fallbacks, "Count"),
        }
        if self.calibration_s is not None:
            metrics["CalibrationSeconds"] = (round(self.calibration_s, 1), "Seconds")
        return emit(metrics, {"event": "session_end"}, stream)
