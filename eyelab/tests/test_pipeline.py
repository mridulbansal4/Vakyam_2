"""End to end on drawn faces: calibrate all seven looks, then select each one by holding it."""

import pytest

from eyecore.looks import CALIBRATION_ORDER, Look
from eyecore.pipeline import EyePipeline, PipelineConfig
from synthetic import FakeDetector, render

TARGETS = [Look.LEFT, Look.RIGHT, Look.UP, Look.LEFT_UP, Look.RIGHT_UP, Look.CLOSED]


class Session:
    def __init__(self, anchor_mode="frozen", pupil_follow=0.6):
        self.detector = FakeDetector()
        self.pipeline = EyePipeline(self.detector, PipelineConfig(anchor_mode=anchor_mode))
        self.follow = pupil_follow
        self.t = 0
        self.seed = 0

    def frame(self, look, **pose):
        self.seed += 1
        self.t += 33
        image, self.detector.face = render(look, pupil_follow=self.follow, seed=self.seed, **pose)
        return self.pipeline.process(image, self.t)

    def calibrate(self, **pose):
        self.pipeline.start_calibration(self.t)
        for look in CALIBRATION_ORDER:
            for _ in range(200):
                if self.frame(look, **pose)["calibration"]["captured"] == look.label:
                    break
            else:
                pytest.fail(f"step {look.label} was never captured")
        assert self.pipeline.calibrated

    def select(self, look, **pose):
        for _ in range(8):
            self.frame(Look.STRAIGHT, **pose)
        for _ in range(30):
            selected = self.frame(look, **pose)["selected"]
            if selected:
                return selected
        return None


@pytest.mark.parametrize("anchor_mode", ["frozen", "landmark"])
@pytest.mark.parametrize("pose", [{}, {"roll_deg": 12}, {"shift": (25, -15)}, {"roll_deg": -10, "shift": (-20, 10)}],
                         ids=["upright", "tilted", "moved", "tilted-and-moved"])
def test_every_look_is_selected_after_calibration(anchor_mode, pose):
    s = Session(anchor_mode)
    s.calibrate()
    assert [s.select(look, **pose) for look in TARGETS] == [look.label for look in TARGETS]


def test_calibration_takes_about_a_second_a_step():
    s = Session()
    s.pipeline.start_calibration(0)
    frames = 0
    while not s.frame(Look.STRAIGHT)["calibration"]["captured"]:
        frames += 1
    assert 1000 <= frames * 33 <= 1500  # 700 ms settle + 400 ms steady hold


def test_a_look_that_matches_an_earlier_step_is_refused():
    s = Session()
    s.pipeline.start_calibration(0)
    while not s.frame(Look.STRAIGHT)["calibration"]["captured"]:
        pass
    statuses = {s.frame(Look.STRAIGHT)["calibration"]["status"] for _ in range(60)}  # asked for left, still straight
    assert "LOOK_FURTHER" in statuses
    assert s.pipeline.calibrator.step == 1


def test_no_face_reports_and_never_selects():
    s = Session()
    s.calibrate()
    s.detector.face = None
    for _ in range(30):
        s.t += 33
        r = s.pipeline.process(render(Look.LEFT)[0], s.t)
        assert r["face"] is None and r["selected"] is None


def test_unknown_anchor_mode_is_rejected():
    with pytest.raises(ValueError):
        EyePipeline(FakeDetector(), PipelineConfig(anchor_mode="nose"))


def test_result_is_json_ready():
    import json
    s = Session()
    s.calibrate()
    json.dumps(s.frame(Look.LEFT))
