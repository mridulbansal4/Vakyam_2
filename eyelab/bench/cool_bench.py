"""
Benchmark the Eye Lab's per-frame OpenCV work with whichever OpenCV build is active (stock OpenCV 5 or COOL).

    python bench/cool_bench.py --label stock --out stock.json
    source /opt/cool/venvs/python_3.12/bin/activate && python bench/cool_bench.py --label cool --out cool.json
    python bench/compare.py stock.json cool.json --price-per-hour 0.0453

Three measurements:
  1. ops      - each OpenCV call the service makes per frame, single-threaded, median and p95 microseconds
  2. frame    - the whole per-frame workload: JPEG decode, YuNet, quality gate, CLAHE, eye crops, matching, dwell
  3. capacity - one worker process per vCPU running the frame workload flat out: frames per second per instance

The environment block records which cv2 was loaded and from where, so the results show which build actually ran.
"""

from __future__ import annotations

import argparse
import json
import math
import multiprocessing as mp
import os
import pathlib
import platform
import statistics
import sys
import time
import urllib.request

EYELAB = pathlib.Path(__file__).resolve().parent.parent
sys.path.insert(0, str(EYELAB))

import cv2  # noqa: E402
import numpy as np  # noqa: E402

MODEL = EYELAB.parent / "app/src/main/assets/face_detection_yunet_2023mar.onnx"


# ---------- environment ----------

def instance_type() -> str | None:
    """EC2 instance type from IMDSv2, or None off EC2."""
    try:
        token = urllib.request.urlopen(urllib.request.Request(
            "http://169.254.169.254/latest/api/token", method="PUT",
            headers={"X-aws-ec2-metadata-token-ttl-seconds": "60"}), timeout=0.5).read().decode()
        return urllib.request.urlopen(urllib.request.Request(
            "http://169.254.169.254/latest/meta-data/instance-type",
            headers={"X-aws-ec2-metadata-token": token}), timeout=0.5).read().decode()
    except Exception:  # noqa: BLE001
        return None


def cpu_model() -> str:
    try:
        for line in open("/proc/cpuinfo", encoding="utf-8"):
            if line.lower().startswith(("model name", "cpu part")):
                return line.split(":", 1)[1].strip()
    except OSError:
        pass
    return platform.processor() or platform.machine()


def environment(label: str) -> dict:
    build = cv2.getBuildInformation()
    interesting = [line.strip() for line in build.splitlines()
                   if any(k in line.lower() for k in ("kleidi", "cpu/hw features", "baseline", "dispatched", "parallel"))]
    return {
        "label": label,
        "opencv_version": cv2.__version__,
        "cv2_file": cv2.__file__,
        "cool_build": "/opt/cool" in cv2.__file__,
        "kleidicv_in_build": "kleidi" in build.lower(),
        "build_info_lines": interesting[:12],
        "machine": platform.machine(),
        "cpu": cpu_model(),
        "vcpus": os.cpu_count(),
        "instance_type": instance_type(),
        "python": platform.python_version(),
        "numpy": np.__version__,
    }


# ---------- inputs ----------

def inputs():
    from simulation.face import render
    from eyecore.looks import Look
    bgr, face = render(Look.LEFT, seed=7)
    frame = cv2.resize(bgr, (480, 360), interpolation=cv2.INTER_AREA)
    ok, jpeg = cv2.imencode(".jpg", frame, [cv2.IMWRITE_JPEG_QUALITY, 75])
    gray = cv2.cvtColor(bgr, cv2.COLOR_BGR2GRAY)
    x, y, w, h = face.box
    face_region = np.ascontiguousarray(gray[max(0, y):y + h, max(0, x):x + w])
    rng = np.random.default_rng(0)
    eye_live = rng.integers(0, 255, (22, 52), dtype=np.uint8)
    eye_core = rng.integers(0, 255, (14, 40), dtype=np.uint8)
    return {"bgr": bgr, "frame": frame, "jpeg": jpeg.tobytes(), "gray": gray, "face": face_region,
            "eye_live": eye_live, "eye_core": eye_core, "landmarks": face.landmarks}


def ops(data: dict) -> dict:
    """Each OpenCV call the service makes per frame (repeat counts as in one frame noted in the name)."""
    from eyecore.quality import FACE_MODEL
    yunet = cv2.FaceDetectorYN.create(str(MODEL), "", (320, 240), 0.6, 0.3, 5)
    small = cv2.resize(data["frame"], (320, 240), interpolation=cv2.INTER_AREA)
    clahe = cv2.createCLAHE(2.0, (4, 4))
    m = np.array([[0.5, 0.05, -100.0], [-0.05, 0.5, -60.0]])
    k = np.array([[640, 0, 320], [0, 640, 240], [0, 0, 1]], np.float64)
    lm = data["landmarks"].astype(np.float64)
    face128 = cv2.resize(data["face"], (128, 166), interpolation=cv2.INTER_AREA)
    return {
        "imdecode jpeg 480x360": lambda: cv2.imdecode(np.frombuffer(data["jpeg"], np.uint8), cv2.IMREAD_COLOR),
        "resize 480x360->320x240 area": lambda: cv2.resize(data["frame"], (320, 240), interpolation=cv2.INTER_AREA),
        "cvtColor bgr->gray 640x480": lambda: cv2.cvtColor(data["bgr"], cv2.COLOR_BGR2GRAY),
        "YuNet detect 320x240": lambda: yunet.detect(small),
        "resize face->128 area": lambda: cv2.resize(data["face"], (128, 166), interpolation=cv2.INTER_AREA),
        "Laplacian 128x166 + var": lambda: cv2.Laplacian(face128, cv2.CV_64F, ksize=3).var(),
        "CLAHE face region": lambda: clahe.apply(data["face"]),
        "warpAffine eye crop 52x22": lambda: cv2.warpAffine(data["face"], m, (52, 22), flags=cv2.INTER_LINEAR,
                                                            borderMode=cv2.BORDER_REPLICATE),
        "matchTemplate 52x22 vs 40x14 (x14/frame)": lambda: cv2.matchTemplate(data["eye_live"], data["eye_core"],
                                                                              cv2.TM_CCOEFF_NORMED),
        "solvePnP SQPnP 5 points": lambda: cv2.solvePnP(FACE_MODEL, lm, k, None, flags=cv2.SOLVEPNP_SQPNP),
    }


def time_op(fn, seconds: float, warmup: int = 20) -> dict:
    for _ in range(warmup):
        fn()
    samples = []
    end = time.perf_counter() + seconds
    while time.perf_counter() < end or len(samples) < 30:
        t = time.perf_counter_ns()
        fn()
        samples.append((time.perf_counter_ns() - t) / 1000)
    samples.sort()
    return {"median_us": round(statistics.median(samples), 2),
            "p95_us": round(samples[int(len(samples) * 0.95)], 2), "runs": len(samples)}


# ---------- the whole frame ----------

class FrameWorkload:
    """What the server does for one frame: decode, YuNet, then the rest of the pipeline on a calibrated session."""

    def __init__(self):
        from eyecore.face import YuNetDetector
        from eyecore.looks import CALIBRATION_ORDER, Look
        from eyecore.pipeline import EyePipeline, PipelineConfig
        from simulation.face import FakeDetector, render
        self.yunet = YuNetDetector(MODEL)
        self.fake = FakeDetector()
        self.pipeline = EyePipeline(self.fake, PipelineConfig(debug_crops_every=0))
        self.render, self.look = render, Look
        t = 0
        self.pipeline.start_calibration(t)
        for look in CALIBRATION_ORDER:
            for i in range(400):
                t += 33
                image, self.fake.face = render(look, seed=i)
                if self.pipeline.process(image, t).get("calibration", {}).get("captured"):
                    break
        assert self.pipeline.calibrated, "benchmark calibration failed"
        self.t = t
        frame, self.face = render(Look.LEFT, seed=99)
        ok, jpeg = cv2.imencode(".jpg", frame, [cv2.IMWRITE_JPEG_QUALITY, 75])
        self.jpeg = jpeg.tobytes()

    def __call__(self):
        self.t += 33
        image = cv2.imdecode(np.frombuffer(self.jpeg, np.uint8), cv2.IMREAD_COLOR)
        self.yunet.detect(image)            # the real detector's cost (the drawn face is not one YuNet finds)
        self.fake.face = self.face          # so the rest of the pipeline runs on a known face
        return self.pipeline.process(image, self.t)


def _worker(seconds: float, queue) -> None:
    cv2.setNumThreads(1)
    work = FrameWorkload()
    for _ in range(10):
        work()
    frames, end = 0, time.perf_counter() + seconds
    while time.perf_counter() < end:
        work()
        frames += 1
    queue.put(frames)


def capacity(workers: int, seconds: float) -> dict:
    ctx = mp.get_context("spawn")
    queue = ctx.Queue()
    procs = [ctx.Process(target=_worker, args=(seconds, queue)) for _ in range(workers)]
    for p in procs:
        p.start()
    total = sum(queue.get() for _ in procs)
    for p in procs:
        p.join()
    fps = total / seconds
    return {"workers": workers, "seconds": seconds, "frames_per_second": round(fps, 1),
            "sessions_at_15fps": math.floor(fps / 15)}


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--label", required=True, help="e.g. stock or cool")
    parser.add_argument("--out", required=True)
    parser.add_argument("--op-seconds", type=float, default=2.0)
    parser.add_argument("--capacity-seconds", type=float, default=20.0)
    parser.add_argument("--workers", type=int, default=os.cpu_count())
    args = parser.parse_args()

    result = {"environment": environment(args.label)}
    print(json.dumps(result["environment"], indent=1))

    cv2.setNumThreads(1)  # one session's frame runs on one core; capacity comes from running many in parallel
    data = inputs()
    result["ops"] = {}
    for name, fn in ops(data).items():
        result["ops"][name] = time_op(fn, args.op_seconds)
        print(f"{name:45s} {result['ops'][name]['median_us']:10.1f} us")

    work = FrameWorkload()
    result["frame"] = time_op(work, args.op_seconds * 2)
    print(f"{'whole frame':45s} {result['frame']['median_us']:10.1f} us")

    result["capacity"] = capacity(args.workers, args.capacity_seconds)
    print(f"capacity: {result['capacity']}")
    pathlib.Path(args.out).write_text(json.dumps(result, indent=1), encoding="utf-8")


if __name__ == "__main__":
    main()
