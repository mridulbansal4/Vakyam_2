"""LookDwellSelector.kt, ported line for line: per-frame looks in, deliberate selections out."""

from __future__ import annotations

UNRELIABLE = -1
STRAIGHT = 0
CLOSED = 5


class LookDwellSelector:
    """
    A look becomes a selection only when held: time based (not frame based), tolerant of one contradicting frame,
    restarted by two in a row or a flickering classification, and blocked from firing twice until the user looks
    away. Closed eyes need a longer hold than a natural blink.
    """

    def __init__(self, dwell_ms: int = 300, closed_dwell_ms: int = 450, min_frames: int = 3, dropout_ms: int = 250,
                 lockout_ms: int = 300, rearm_neutral_frames: int = 3, rearm_absence_ms: int = 600):
        self.dwell_ms = dwell_ms
        self.closed_dwell_ms = closed_dwell_ms
        self.min_frames = min_frames
        self.dropout_ms = dropout_ms
        self.lockout_ms = lockout_ms
        self.rearm_neutral_frames = rearm_neutral_frames
        self.rearm_absence_ms = rearm_absence_ms
        self.reset()

    def reset(self) -> None:
        self.cancel()
        self._blocked = UNRELIABLE
        self._blocked_seen_ms = 0
        self._neutral_frames = 0
        self._last_fire_ms = -1
        self._last_frame_ms = -1

    def cancel(self) -> None:
        """Drops the look being held, e.g. when the head turns away."""
        self._candidate = UNRELIABLE
        self._candidate_start_ms = 0
        self._candidate_seen_ms = 0
        self._candidate_frames = 0
        self._contradictions = 0
        self._challenger = UNRELIABLE
        self._challenger_start_ms = 0

    def progress(self, now_ms: int) -> float:
        """0..1 of the hold completed for the current candidate, for a fill animation."""
        if self._candidate in (UNRELIABLE, STRAIGHT, self._blocked):
            return 0.0
        required = self.closed_dwell_ms if self._candidate == CLOSED else self.dwell_ms
        return max(0.0, min(1.0, (now_ms - self._candidate_start_ms) / required))

    @property
    def candidate(self) -> int:
        return self._candidate

    def update(self, look: int, now_ms: int) -> int:
        """Returns the selected look tag on the frame the hold completes, otherwise 0."""
        if self._last_frame_ms >= 0 and now_ms < self._last_frame_ms:
            self.reset()  # clock went backwards
        self._last_frame_ms = now_ms

        if self._blocked != UNRELIABLE:
            if look == self._blocked:
                self._blocked_seen_ms = now_ms
                self._neutral_frames = 0
            else:
                if look == STRAIGHT:
                    self._neutral_frames += 1
                elif look != UNRELIABLE:
                    self._neutral_frames = 0
                if (self._neutral_frames >= self.rearm_neutral_frames
                        or now_ms - self._blocked_seen_ms >= self.rearm_absence_ms):
                    self._blocked = UNRELIABLE

        if self._last_fire_ms >= 0 and now_ms - self._last_fire_ms < self.lockout_ms:
            self.cancel()
            return 0
        if self._candidate != UNRELIABLE and now_ms - self._candidate_seen_ms > self.dropout_ms:
            self.cancel()
        if look == UNRELIABLE:
            return 0

        if look == self._candidate:
            self._candidate_frames += 1
            self._candidate_seen_ms = now_ms
            self._challenger = UNRELIABLE
        elif look == self._challenger:
            self._start(look, self._challenger_start_ms, now_ms, 2)
        else:
            if self._candidate != UNRELIABLE:
                self._contradictions += 1
            if self._candidate == UNRELIABLE or self._contradictions * 4 > self._candidate_frames:
                self._start(look, now_ms, now_ms, 1)
            else:
                self._challenger = look
                self._challenger_start_ms = now_ms

        if look != self._candidate or self._candidate == STRAIGHT or self._candidate == self._blocked:
            return 0
        required = self.closed_dwell_ms if self._candidate == CLOSED else self.dwell_ms
        if self._candidate_frames < self.min_frames or now_ms - self._candidate_start_ms < required:
            return 0
        selected = self._candidate
        self._last_fire_ms = now_ms
        self._blocked = selected
        self._blocked_seen_ms = now_ms
        self._neutral_frames = 0
        self.cancel()
        return selected

    def _start(self, look: int, start_ms: int, now_ms: int, frames: int) -> None:
        self._candidate = look
        self._candidate_start_ms = start_ms
        self._candidate_seen_ms = now_ms
        self._candidate_frames = frames
        self._contradictions = 0
        self._challenger = UNRELIABLE
