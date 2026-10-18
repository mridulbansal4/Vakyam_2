"""Template matching, as EyeDetection.runMatchTemplateModel and Model.analyzeLookOutput on the phone."""

from __future__ import annotations

from dataclasses import dataclass

import cv2
import numpy as np

TEMPLATE_MARGIN = 2           # cut off each side of a stored crop, as EyeDetection.TEMPLATE_MARGIN
CLEAR_MATCH_RATIO = 0.9       # best must beat the runner-up by this factor, as EyeDetection.CLEAR_MATCH_RATIO
NO_MATCH = 1_000_000.0
STRAIGHT = 0                  # Look.STRAIGHT


def template_core(stored: np.ndarray) -> np.ndarray:
    m = TEMPLATE_MARGIN
    return stored[m:stored.shape[0] - m, m:stored.shape[1] - m].copy()


def template_error(live: np.ndarray, core: np.ndarray) -> float:
    """1 - best TM_CCOEFF_NORMED over every placement of the template in the live crop. 0 = identical, ~1 = unrelated."""
    if live is None or core is None or live.shape[0] < core.shape[0] or live.shape[1] < core.shape[1]:
        return NO_MATCH
    best = float(cv2.matchTemplate(live, core, cv2.TM_CCOEFF_NORMED).max())
    return 1.0 if np.isnan(best) else 1.0 - best   # a flat crop has no correlation


def threshold_for(sensitivity: int) -> float:
    """VisionPipeline.OPENCV5.matchThreshold: the largest per-eye error that still counts as a match."""
    setting = sensitivity if sensitivity > 0 else 20
    return min(setting / 50.0, 2.0)


def is_clear_match(best: float, second: float) -> bool:
    return best <= second * CLEAR_MATCH_RATIO


@dataclass(frozen=True)
class Match:
    look: int | None          # best look tag, None when nothing was compared
    ok: bool                  # under the threshold and clearly better than the runner-up
    error: float              # summed error of the eyes that were compared
    eyes: int                 # how many eyes took part
    errors: dict[int, float]  # summed error per look tag


def classify(live_left: np.ndarray | None, live_right: np.ndarray | None,
             cores: dict[int, tuple[np.ndarray, np.ndarray]], sensitivity: int, rest_bias: float = 0.0) -> Match:
    """Sums both eyes' errors per look, as the phone does when the eyes disagree, which is also right when they agree.

    rest_bias scales looking straight's error down by that fraction before ranking, so a target must beat resting
    by a margin: resting is the most common look, and the one whose misreading types something unasked for.
    """
    eyes = [(i, crop) for i, crop in enumerate((live_left, live_right)) if crop is not None]
    if not eyes or not cores:
        return Match(None, False, NO_MATCH, 0, {})
    errors = {look: sum(template_error(crop, pair[i]) for i, crop in eyes) for look, pair in cores.items()}
    if rest_bias and STRAIGHT in errors:
        errors[STRAIGHT] *= 1.0 - rest_bias
    ranked = sorted(errors.items(), key=lambda kv: kv[1])
    best_look, best = ranked[0]
    second = ranked[1][1] if len(ranked) > 1 else NO_MATCH
    ok = best <= threshold_for(sensitivity) * len(eyes) and is_clear_match(best, second)
    return Match(int(best_look), ok, best, len(eyes), errors)
