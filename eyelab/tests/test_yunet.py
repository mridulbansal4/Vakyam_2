"""The real YuNet model from the Android app's assets, on OpenCV 5."""

import os

import cv2
import numpy as np
import pytest

from eyecore.face import YuNetDetector
from eyecore.quality import measure


def test_model_loads_and_finds_no_face_in_a_blank_frame(model_path):
    assert YuNetDetector(model_path).detect(np.full((480, 640, 3), 128, np.uint8)) is None


@pytest.mark.skipif(not os.environ.get("EYELAB_FACE_IMAGE"), reason="set EYELAB_FACE_IMAGE to a photo of a face")
def test_real_face(model_path):
    image = cv2.imread(os.environ["EYELAB_FACE_IMAGE"])
    face = YuNetDetector(model_path).detect(image)
    assert face is not None and face.score > 0.6
    assert face.left_eye[0] < face.right_eye[0]
    quality = measure(cv2.cvtColor(image, cv2.COLOR_BGR2GRAY), face)
    assert quality is not None and quality.sharpness > 0
