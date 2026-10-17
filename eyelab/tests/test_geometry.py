import math

import numpy as np
import pytest

from eyecore.face import Face
from eyecore.geometry import (CROP_HEIGHT, CROP_WIDTH, EYE_CROP_WIDTH, LIVE_HEIGHT, LIVE_WIDTH, CropGeometry,
                              EyeAnchors, center_of_live, eye_geometry)
from eyecore.looks import Look
from synthetic import render


def test_forward_and_inverse_agree():
    g = CropGeometry(310.0, 190.0, 0.3, 0.7, 52, 22)
    for x, y in [(0, 0), (26, 11), (51, 21), (10.5, 3.25)]:
        fx, fy = g.to_frame(x, y)
        bx, by = g.to_crop(fx, fy)
        assert bx == pytest.approx(x, abs=1e-9) and by == pytest.approx(y, abs=1e-9)


def test_matrix_accounts_for_a_sub_image_origin():
    g = CropGeometry(300.0, 200.0, 0.2, 0.8, 52, 22)
    m = g.forward_matrix(180, 90)
    via_region = m @ np.array([300 - 180, 200 - 90, 1.0])
    assert via_region == pytest.approx(g.to_crop(300, 200), abs=1e-9)


def test_eye_centre_lands_in_the_middle_of_the_crop():
    _, face = render(Look.STRAIGHT, pupil_follow=0.0)
    g = eye_geometry(face.left_eye, face)
    cx, cy = g.to_crop(*face.left_eye)
    assert (cx, cy) == pytest.approx((LIVE_WIDTH / 2, LIVE_HEIGHT / 2), abs=1e-9)
    assert g.scale == pytest.approx(CROP_WIDTH / (EYE_CROP_WIDTH * face.eye_distance))


def test_frozen_anchors_ignore_where_the_eyes_look():
    # Calibrate on a straight look, then look left with eye points that follow the pupils fully
    _, straight = render(Look.STRAIGHT, pupil_follow=1.0, roll_deg=8, shift=(10, -5))
    anchors = EyeAnchors.from_face(straight)
    _, left = render(Look.LEFT, pupil_follow=1.0, roll_deg=8, shift=(10, -5))
    for frozen, true_eye in zip(anchors.centers(left), (straight.left_eye, straight.right_eye)):
        assert frozen == pytest.approx(true_eye, abs=1e-6)
    # ...while the raw eye points moved by the full pupil offset
    assert np.linalg.norm(left.left_eye - straight.left_eye) == pytest.approx(9.0, abs=1e-6)


def test_frozen_anchors_follow_the_head():
    _, straight = render(Look.STRAIGHT, pupil_follow=0.0)
    anchors = EyeAnchors.from_face(straight)
    _, moved = render(Look.STRAIGHT, pupil_follow=0.0, roll_deg=15, shift=(40, 20))
    for a, b in zip(anchors.centers(moved), (moved.left_eye, moved.right_eye)):
        assert a == pytest.approx(b, abs=1e-6)


def test_mean_of_anchors():
    a = EyeAnchors((0.0, 0.0), (1.0, 1.0))
    b = EyeAnchors((2.0, 4.0), (3.0, 5.0))
    m = EyeAnchors.mean([a, b])
    assert m.left == (1.0, 2.0) and m.right == (2.0, 3.0)


def test_rejects_tiny_or_steeply_rolled_faces():
    lm = np.array([[100, 100], [104, 100], [102, 104], [100, 108], [104, 108]], float)
    assert eye_geometry(lm[0], Face(90, 90, 20, 20, 0.9, lm)) is None
    lm = np.array([[100, 100], [140, 160], [130, 150], [100, 190], [140, 200]], float)
    assert math.degrees(Face(80, 80, 100, 140, 0.9, lm).roll) > 45
    assert eye_geometry(lm[0], Face(80, 80, 100, 140, 0.9, lm)) is None


def test_stored_crop_is_the_centre_of_the_live_crop():
    live = np.arange(LIVE_WIDTH * LIVE_HEIGHT, dtype=np.uint16).reshape(LIVE_HEIGHT, LIVE_WIDTH)
    stored = center_of_live(live)
    assert stored.shape == (CROP_HEIGHT, CROP_WIDTH)
    assert stored[0, 0] == live[(LIVE_HEIGHT - CROP_HEIGHT) // 2, (LIVE_WIDTH - CROP_WIDTH) // 2]
