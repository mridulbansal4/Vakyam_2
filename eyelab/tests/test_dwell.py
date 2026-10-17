from eyecore.dwell import CLOSED, STRAIGHT, UNRELIABLE, LookDwellSelector

LEFT, RIGHT = 1, 2


def feed(selector, look, start_ms, frames, step_ms=33):
    fired = []
    for i in range(frames):
        out = selector.update(look, start_ms + i * step_ms)
        if out:
            fired.append(out)
    return fired, start_ms + frames * step_ms


def test_held_look_fires_once_after_the_dwell():
    s = LookDwellSelector()
    fired, _ = feed(s, LEFT, 0, 30)
    assert fired == [LEFT]


def test_short_glance_does_not_fire():
    s = LookDwellSelector()
    fired, _ = feed(s, LEFT, 0, 8)  # 264 ms < 300 ms
    assert fired == []


def test_straight_never_fires():
    s = LookDwellSelector()
    fired, _ = feed(s, STRAIGHT, 0, 60)
    assert fired == []


def test_natural_blink_is_not_entered_but_a_long_close_is():
    s = LookDwellSelector()
    fired, t = feed(s, CLOSED, 0, 10)  # 330 ms: a blink
    assert fired == []
    fired, _ = feed(s, STRAIGHT, t, 5)
    fired, _ = feed(s, CLOSED, t + 200, 20)  # 660 ms
    assert fired == [CLOSED]


def test_one_contradicting_frame_is_tolerated():
    s = LookDwellSelector()
    _, t = feed(s, LEFT, 0, 5)
    s.update(UNRELIABLE, t)
    fired, _ = feed(s, LEFT, t + 33, 10)
    assert fired == [LEFT]


def test_same_look_must_be_released_before_firing_again():
    s = LookDwellSelector()
    fired, t = feed(s, LEFT, 0, 60)
    assert fired == [LEFT]
    fired, t = feed(s, STRAIGHT, t, 4)
    fired, _ = feed(s, LEFT, t, 30)
    assert fired == [LEFT]


def test_cancel_drops_the_hold():
    s = LookDwellSelector()
    _, t = feed(s, RIGHT, 0, 7)
    s.cancel()
    fired, _ = feed(s, RIGHT, t, 7)
    assert fired == []


def test_progress_rises_while_holding():
    s = LookDwellSelector()
    s.update(LEFT, 0)
    s.update(LEFT, 150)
    assert 0.45 < s.progress(150) < 0.55
    assert s.progress(1000) == 1.0
