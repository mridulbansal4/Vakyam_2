"""
Replays a recorded session (server/recorder.py) through a fresh pipeline and prints its look test report, so a
change to the matcher can be judged on a real face before anyone sits in front of the camera again.

    python -m bench.replay <recording folder>                       # as recorded
    python -m bench.replay <recording folder> --rest-bias 0.15      # one setting changed
    python -m bench.replay <recording folder> --sweep               # rest bias x sensitivity grid

Face detection does not depend on any setting here, so it runs once and is reused for every configuration.
"""

from __future__ import annotations

import argparse
import itertools
import json
import pathlib
import sys

import cv2

ROOT = pathlib.Path(__file__).resolve().parent.parent
sys.path.insert(0, str(ROOT))

from eyecore.face import YuNetDetector  # noqa: E402
from eyecore.looks import Look  # noqa: E402
from eyecore.pipeline import EyePipeline, PipelineConfig  # noqa: E402
from server.config import Settings  # noqa: E402


class CachedDetector:
    """YuNet once per frame, then the same answers on every later pass."""

    def __init__(self, model_path: str):
        self.yunet = YuNetDetector(model_path)
        self.cache: dict[int, object] = {}
        self.index = 0

    def detect(self, bgr):
        if self.index not in self.cache:
            self.cache[self.index] = self.yunet.detect(bgr)
        return self.cache[self.index]


def load(folder: pathlib.Path) -> list[dict]:
    return [json.loads(line) for line in (folder / "events.jsonl").read_text(encoding="utf-8").splitlines() if line]


def replay(folder: pathlib.Path, events: list[dict], detector: CachedDetector, config: PipelineConfig,
           fixed_sensitivity: bool) -> dict | None:
    pipeline = EyePipeline(detector, config)
    detector.index = -1
    report = None
    for event in events:
        t = event["t"]
        if event.get("control") == "calibrate":
            pipeline.start_calibration(t)
        elif event.get("control") == "look_test" and pipeline.calibrated:
            order = event.get("order")
            pipeline.start_look_test(t, tuple(Look[name.upper().replace("-", "_")] for name in order) if order else None)
        elif event.get("control") == "settings" and not fixed_sensitivity:
            pipeline.config.sensitivity = event["sensitivity"]
        elif "frame" in event:
            detector.index += 1
            image = cv2.imread(str(folder / event["frame"]), cv2.IMREAD_COLOR)
            result = pipeline.process(image, t)
            test = result.get("look_test") or {}
            if test.get("finished"):
                report = test["report"]
    return report


def summary(report: dict | None) -> str:
    if report is None:
        return "no finished look test in this recording"
    looks = report["looks"]
    parts = [f"accuracy {report['accuracy']:.0%}", f"wrong selections {report['wrong_selections']}",
             f"resting read as up {report['resting_read_as_up']:.0%}"]
    parts += [f"{name} {v['correct']:.0%}" for name, v in looks.items()]
    return " | ".join(parts)


def main() -> None:
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("folder", type=pathlib.Path)
    p.add_argument("--sensitivity", type=int, help="fixed sensitivity (default: as recorded)")
    p.add_argument("--rest-bias", type=float, default=0.0)
    p.add_argument("--head-moved-deg", type=float, default=PipelineConfig.head_moved_deg)
    p.add_argument("--sweep", action="store_true", help="rest bias 0..0.3 x sensitivity 15..30")
    p.add_argument("--json", action="store_true", help="print the full report of each run")
    args = p.parse_args()

    events = load(args.folder)
    detector = CachedDetector(Settings.from_env().model_path)
    if args.sweep:
        grid = list(itertools.product([0.0, 0.05, 0.1, 0.15, 0.2, 0.3], [15, 20, 25, 30]))
    else:
        grid = [(args.rest_bias, args.sensitivity)]
    for bias, sensitivity in grid:
        config = PipelineConfig(rest_bias=bias, head_moved_deg=args.head_moved_deg,
                                **({"sensitivity": sensitivity} if sensitivity else {}))
        report = replay(args.folder, events, detector, config, fixed_sensitivity=sensitivity is not None)
        print(f"rest_bias={bias:<5} sensitivity={sensitivity or 'recorded':<8} {summary(report)}")
        if args.json:
            print(json.dumps(report, indent=2))


if __name__ == "__main__":
    main()
