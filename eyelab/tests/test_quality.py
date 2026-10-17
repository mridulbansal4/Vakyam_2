import math
from dataclasses import replace

import numpy as np
import pytest

from eyecore.quality import FaceQuality, FaceQualityGate, QualityIssue, euler_degrees, issues_of

GOOD = FaceQuality(face_width_px=200, sharpness=900, face_luma=120, frame_luma=110, clipped_fraction=0.01,
                   yaw_deg=5, pitch_deg=-4, roll_deg=2, face_score=0.9)


def test_good_face_has_no_issues():
    assert issues_of(GOOD) == set()


@pytest.mark.parametrize("change,issue", [
    (dict(yaw_deg=-40), QualityIssue.TURNED_AWAY),
    (dict(face_width_px=60), QualityIssue.TOO_FAR),
    (dict(face_luma=30, frame_luma=30), QualityIssue.TOO_DARK),
    (dict(face_luma=70, frame_luma=170), QualityIssue.BACKLIT),
    (dict(clipped_fraction=0.3), QualityIssue.OVEREXPOSED),
    (dict(sharpness=80), QualityIssue.BLURRY),
    (dict(pitch_deg=38), QualityIssue.TILTED),
])
def test_each_measurement_raises_its_own_issue(change, issue):
    assert issues_of(replace(GOOD, **change)) == {issue}


def test_unmeasured_values_never_raise_issues():
    assert issues_of(FaceQuality()) == set()


def test_only_turning_away_blocks_input():
    assert [i for i in QualityIssue if i.blocks_input] == [QualityIssue.TURNED_AWAY]


def test_issue_is_held_before_it_is_reported_and_clears_at_once():
    gate = FaceQualityGate(hold_ms=400)
    turned = replace(GOOD, yaw_deg=50)
    gate.update(turned, 0)
    assert gate.current == {QualityIssue.TURNED_AWAY} and gate.active == set()
    assert not gate.ok_for_calibration
    gate.update(turned, 400)
    assert gate.blocks_input and gate.primary == QualityIssue.TURNED_AWAY
    gate.update(GOOD, 420)
    assert not gate.blocks_input and gate.primary is None and gate.ok_for_calibration


def test_primary_follows_the_fix_order():
    gate = FaceQualityGate(hold_ms=0)
    gate.update(replace(GOOD, sharpness=10, face_width_px=40, yaw_deg=45), 0)
    assert gate.primary == QualityIssue.TURNED_AWAY


def test_euler_angles_match_the_android_head_pose():
    t = math.radians(25)
    c, s = math.cos(t), math.sin(t)
    assert euler_degrees(np.array([[c, 0, s], [0, 1, 0], [-s, 0, c]])) == pytest.approx((25, 0, 0))
    assert euler_degrees(np.array([[1, 0, 0], [0, c, -s], [0, s, c]])) == pytest.approx((0, 25, 0))
    assert euler_degrees(np.array([[c, -s, 0], [s, c, 0], [0, 0, 1]])) == pytest.approx((0, 0, 25))
