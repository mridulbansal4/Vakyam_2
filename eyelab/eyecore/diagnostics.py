"""
OpenCV 5 measurements the Calibration Coach reasons over. Each one is computed from images, so what the coach
does next depends on what the camera actually saw.
"""

from __future__ import annotations

from itertools import combinations
from statistics import median

from .calibration import Calibration
from .looks import Look
from .matching import template_core, template_error

# Two calibrated looks closer than this (1 - correlation, averaged over both eyes and both directions) are at
# risk of being mistaken for each other. Synthetic calibrations sit at 0.25 or more; a user who only glanced a
# little way up measured 0.12, and the calibrator itself refuses anything under 0.06.
CONFUSABLE_DISTANCE = 0.15


def template_separability(calibration: Calibration) -> dict:
    """How far apart every pair of calibrated looks is, by matchTemplate between the stored crops."""
    pairs = []
    looks = sorted(calibration.crops)
    for a, b in combinations(looks, 2):
        (la, ra), (lb, rb) = calibration.crops[a], calibration.crops[b]
        d = (template_error(la, template_core(lb)) + template_error(lb, template_core(la))
             + template_error(ra, template_core(rb)) + template_error(rb, template_core(ra))) / 4
        pairs.append({"looks": [Look(a).label, Look(b).label], "distance": round(d, 4)})
    pairs.sort(key=lambda p: p["distance"])
    return {
        "calibrated": [Look(x).label for x in looks],
        "closest_pairs": pairs[:5],
        "confusable": [p for p in pairs if p["distance"] < CONFUSABLE_DISTANCE],
        "threshold": CONFUSABLE_DISTANCE,
    }


def camera_report(results: list[dict]) -> dict:
    """Summary of recent pipeline results: is there a face, and is the image good enough?"""
    n = len(results)
    if n == 0:
        return {"frames": 0, "face_found_rate": 0.0, "issues": {}, "note": "no frames received"}
    with_face = [r for r in results if r.get("face")]
    qualities = [r["quality"] for r in with_face if r.get("quality")]

    def med(key):
        values = [q[key] for q in qualities if q.get(key) is not None]
        return round(median(values), 1) if values else None

    issues: dict[str, float] = {}
    for r in results:
        for issue in r.get("issues", []):
            issues[issue] = issues.get(issue, 0) + 1
    return {
        "frames": n,
        "face_found_rate": round(len(with_face) / n, 2),
        "face_width_px": med("face_width_px"),
        "face_brightness": med("face_luma"),
        "frame_brightness": med("frame_luma"),
        "sharpness": med("sharpness"),
        "yaw_deg": med("yaw_deg"),
        "pitch_deg": med("pitch_deg"),
        "issues": {k: round(v / n, 2) for k, v in sorted(issues.items(), key=lambda kv: -kv[1])},
    }


def self_test_report(results: list[dict], target: Look) -> dict:
    """How well one look was recognised while the user held it. Errors are per eye, like the threshold."""
    scored = [r["look"] for r in results if r.get("look")]
    scored = [{**s, "errors": {k: v / max(1, s.get("eyes", 2)) for k, v in s.get("errors", {}).items()}}
              for s in scored]
    n = len(scored)
    if n == 0:
        return {"look": target.label, "frames": 0, "recognised_rate": 0.0, "note": "no eyes found while testing"}
    hits = [s for s in scored if s["ok"] and s["name"] == target.label]
    best_is_target = [s for s in scored if s["name"] == target.label]
    confusions: dict[str, int] = {}
    for s in scored:
        if s["name"] != target.label and s["name"] is not None:
            confusions[s["name"]] = confusions.get(s["name"], 0) + 1
    target_errors = [s["errors"].get(target.label) for s in scored if target.label in s.get("errors", {})]
    margins = []
    for s in scored:
        others = sorted(v for k, v in s.get("errors", {}).items() if k != target.label)
        if target.label in s.get("errors", {}) and others:
            margins.append(others[0] - s["errors"][target.label])
    return {
        "look": target.label,
        "frames": n,
        "recognised_rate": round(len(hits) / n, 2),
        "best_match_rate": round(len(best_is_target) / n, 2),
        "median_error": round(median(target_errors), 3) if target_errors else None,
        "median_margin": round(median(margins), 3) if margins else None,
        "confused_with": dict(sorted(confusions.items(), key=lambda kv: -kv[1])),
    }
