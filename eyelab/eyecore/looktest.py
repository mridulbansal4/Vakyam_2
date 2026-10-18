"""
The look test: about a minute of prompted looks after calibration, scored frame by frame.

It answers the question a user cannot answer by typing: which looks are read as which. Looking straight comes
between every target, because resting your eyes is what a user does most, and a resting look read as a target
types something nobody asked for. The test is driven by time alone, so a recorded session replays to the same
labels (bench/replay.py).
"""

from __future__ import annotations

from collections import Counter
from dataclasses import dataclass, field

from .looks import CALIBRATION_PROMPTS, Look

SETTLE_MS = 1200     # after each prompt, time to get there; not scored
RECORD_MS = 2300     # then every frame is scored against the prompted look

S = Look.STRAIGHT
ORDER = (S, Look.UP, S, Look.LEFT_UP, S, Look.RIGHT_UP, S, Look.LEFT, S, Look.RIGHT, S, Look.CLOSED, S, Look.UP, S)
PROMPTS = {**CALIBRATION_PROMPTS, Look.CLOSED: "Close your eyes until the next beep."}


@dataclass
class Tally:
    frames: int = 0
    correct: int = 0                                    # accepted as the prompted look
    unsure: int = 0                                     # nothing accepted (no face, no clear match)
    wrong: Counter = field(default_factory=Counter)     # accepted as another look
    selections: Counter = field(default_factory=Counter)  # what the dwell selector fired, by look
    pitch: list = field(default_factory=list)

    def to_json(self, target: Look) -> dict:
        n = max(1, self.frames)
        fired_wrong = sum(v for k, v in self.selections.items() if k != target.label)
        pitches = sorted(self.pitch)
        return {
            "frames": self.frames, "correct": round(self.correct / n, 3), "unsure": round(self.unsure / n, 3),
            "wrong": round(sum(self.wrong.values()) / n, 3),
            "read_as": {k: round(v / n, 3) for k, v in self.wrong.most_common()},
            "wrong_selections": fired_wrong,
            "selections": dict(self.selections),
            "pitch_deg": round(pitches[len(pitches) // 2], 1) if pitches else None,
        }


class LookTest:
    def __init__(self, order: tuple[Look, ...] = ORDER):
        self.order = order
        self.running = False
        self.step = 0
        self._start_ms = 0
        self.tallies: dict[Look, Tally] = {}

    def start(self, now_ms: int, order: tuple[Look, ...] | None = None) -> dict:
        if order is not None:
            self.order = tuple(order)
        self.running = True
        self.step = 0
        self._start_ms = now_ms
        self.tallies = {look: Tally() for look in dict.fromkeys(self.order)}
        return self._event(0.0, reprompt=True)

    def cancel(self) -> None:
        self.running = False

    def label_at(self, now_ms: int) -> tuple[int, Look | None, bool]:
        """(step, prompted look, whether this moment is scored); look is None once the test is over."""
        elapsed = now_ms - self._start_ms
        step = elapsed // (SETTLE_MS + RECORD_MS)
        if step >= len(self.order):
            return len(self.order), None, False
        return int(step), self.order[step], elapsed % (SETTLE_MS + RECORD_MS) >= SETTLE_MS

    def update(self, look: dict | None, selected: str | None, pitch: float | None, now_ms: int) -> dict:
        """look is the pipeline's look result (None without a face); selected is what the dwell selector fired."""
        step, target, scored = self.label_at(now_ms)
        if target is None:
            self.running = False
            return {**self._event(1.0), "finished": True, "report": self.report()}
        reprompt = step != self.step
        self.step = step
        if scored:
            tally = self.tallies[target]
            tally.frames += 1
            if look is None or not look.get("ok"):
                tally.unsure += 1
            elif look["name"] == target.label:
                tally.correct += 1
            else:
                tally.wrong[look["name"]] += 1
            if selected:
                tally.selections[selected] += 1
            if pitch is not None:
                tally.pitch.append(pitch)
        into = (now_ms - self._start_ms) % (SETTLE_MS + RECORD_MS)
        return self._event(max(0.0, (into - SETTLE_MS) / RECORD_MS), reprompt=reprompt, scored=scored)

    def report(self) -> dict:
        looks = {look.label: tally.to_json(look) for look, tally in self.tallies.items()}
        frames = sum(t.frames for t in self.tallies.values())
        straight = self.tallies.get(Look.STRAIGHT)
        return {
            "looks": looks,
            "accuracy": round(sum(t.correct for t in self.tallies.values()) / max(1, frames), 3),
            "wrong_selections": sum(v["wrong_selections"] for v in looks.values()),
            "resting_read_as_up": round(straight.wrong["up"] / max(1, straight.frames), 3) if straight else None,
        }

    def _event(self, progress: float, reprompt: bool = False, scored: bool = False) -> dict:
        look = self.order[min(self.step, len(self.order) - 1)]
        return {"running": self.running, "step": self.step, "steps": len(self.order), "look": look.label,
                "prompt": PROMPTS[look], "status_text": "Hold that look…" if scored else "Get ready…",
                "progress": round(min(1.0, progress), 3), "reprompt": reprompt, "finished": False}
