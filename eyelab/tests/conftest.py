import pathlib
import sys

import pytest

EYELAB = pathlib.Path(__file__).resolve().parent.parent
sys.path[:0] = [str(EYELAB)]

MODEL = EYELAB.parent / "app/src/main/assets/face_detection_yunet_2023mar.onnx"


@pytest.fixture(scope="session")
def model_path() -> pathlib.Path:
    assert MODEL.exists(), f"YuNet model missing at {MODEL}"
    return MODEL
