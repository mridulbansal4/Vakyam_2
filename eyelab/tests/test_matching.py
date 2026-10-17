import numpy as np
import pytest

from eyecore.matching import NO_MATCH, classify, is_clear_match, template_core, template_error, threshold_for


def eye(cx, cy, w=52, h=22, seed=0):
    rng = np.random.default_rng(seed)
    yy, xx = np.mgrid[0:h, 0:w]
    img = np.where((xx - cx) ** 2 + (yy - cy) ** 2 <= 30, 30.0, 200.0) + rng.normal(0, 2, (h, w))
    return np.clip(img, 0, 255).astype(np.uint8)


def stored(live):
    return live[2:20, 4:48]


def test_identical_crops_have_no_error():
    live = eye(26, 11)
    assert template_error(live, template_core(stored(live))) == pytest.approx(0.0, abs=1e-6)


def test_a_few_pixels_of_crop_jitter_are_absorbed():
    calibrated = template_core(stored(eye(26, 11)))
    jittered = np.roll(eye(26, 11, seed=1), 3, axis=1)
    assert template_error(jittered, calibrated) < 0.05


def test_brightness_and_contrast_changes_cancel_out():
    live = eye(26, 11)
    brighter = np.clip(live.astype(float) * 0.7 + 40, 0, 255).astype(np.uint8)
    assert template_error(brighter, template_core(stored(live))) < 0.01


def test_a_different_look_scores_worse():
    straight = template_core(stored(eye(26, 11)))
    assert template_error(eye(14, 11), straight) > 0.3


def test_crop_smaller_than_template_is_no_match():
    assert template_error(np.zeros((5, 5), np.uint8), np.zeros((14, 40), np.uint8)) == NO_MATCH


def test_threshold_matches_the_phone_setting():
    assert threshold_for(20) == pytest.approx(0.4)
    assert threshold_for(0) == pytest.approx(0.4)
    assert threshold_for(255) == 2.0


def test_classify_picks_the_calibrated_look():
    cores = {0: (template_core(stored(eye(26, 11))),) * 2, 1: (template_core(stored(eye(14, 11))),) * 2,
             2: (template_core(stored(eye(38, 11))),) * 2}
    m = classify(eye(14, 11, seed=5), eye(14, 11, seed=6), cores, 20)
    assert m.look == 1 and m.ok and m.eyes == 2


def test_one_missing_eye_still_classifies():
    cores = {0: (template_core(stored(eye(26, 11))),) * 2, 2: (template_core(stored(eye(38, 11))),) * 2}
    m = classify(None, eye(38, 11, seed=3), cores, 20)
    assert m.look == 2 and m.ok and m.eyes == 1


def test_ambiguous_match_is_rejected():
    assert not is_clear_match(0.10, 0.105)
    assert is_clear_match(0.05, 0.2)


def test_nothing_to_compare():
    assert classify(None, None, {}, 20).look is None
