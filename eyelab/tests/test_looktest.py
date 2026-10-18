"""The look test, the moved-head guard, the resting bias, and recording a session then replaying it."""

import json


from bench.replay import replay
from eyecore.looks import Look
from eyecore.looktest import ORDER, RECORD_MS, SETTLE_MS, LookTest
from eyecore.matching import classify
from eyecore.pipeline import PipelineConfig
from eyecore.quality import QualityIssue
from server.recorder import SessionRecorder
from simulation.face import render
from tests.test_pipeline import Session

STEP_MS = SETTLE_MS + RECORD_MS
# Each look once, resting between: half the real test, which is the same pattern twice over
SHORT = (Look.STRAIGHT, Look.UP, Look.STRAIGHT, Look.LEFT_UP, Look.RIGHT_UP, Look.LEFT, Look.RIGHT, Look.CLOSED)


def run_look_test(session, order=SHORT, **pose):
    session.pipeline.start_look_test(session.t, order)
    selections, results = [], []
    for _ in range(len(order) * STEP_MS // 33 + 10):
        _, target, _ = session.pipeline.look_test.label_at(session.t + 33)
        result = session.frame(target if target is not None else Look.STRAIGHT, **pose)
        results.append(result)
        selections.append(result["selected"])
        if (result.get("look_test") or {}).get("finished"):
            return result["look_test"]["report"], selections, results
    raise AssertionError("the look test never finished")


def test_straight_comes_between_every_target():
    assert all(ORDER[i] == Look.STRAIGHT for i in range(0, len(ORDER), 2))
    assert set(ORDER) == {Look.STRAIGHT, Look.UP, Look.LEFT_UP, Look.RIGHT_UP, Look.LEFT, Look.RIGHT, Look.CLOSED}


def test_labels_come_from_time_alone():
    test = LookTest()
    test.start(1000)
    assert test.label_at(1000) == (0, Look.STRAIGHT, False)
    assert test.label_at(1000 + SETTLE_MS) == (0, Look.STRAIGHT, True)
    assert test.label_at(1000 + STEP_MS) == (1, Look.UP, False)
    assert test.label_at(1000 + STEP_MS * len(ORDER)) == (len(ORDER), None, False)


def test_a_clean_session_scores_every_look_right_and_types_nothing():
    s = Session()
    s.calibrate()
    report, selections, _ = run_look_test(s)
    assert not any(selections)                        # the test never types
    assert report["wrong_selections"] == 0
    assert report["resting_read_as_up"] == 0
    for name, look in report["looks"].items():
        assert look["correct"] > 0.9, (name, look)


def test_a_rest_biased_match_needs_a_margin_over_straight():
    s = Session()
    s.calibrate()
    image_halfway, s.detector.face = render(Look.UP, iris=(0, -2), seed=99)   # between straight and up
    live = s.pipeline.eye_crops(image_halfway, s.detector.face, image_halfway[:, :, 0])
    cores = s.pipeline.calibrator.calibration.cores()
    plain = classify(live[0], live[1], cores, 20)
    biased = classify(live[0], live[1], cores, 20, rest_bias=0.5)
    assert biased.errors[int(Look.STRAIGHT)] < plain.errors[int(Look.STRAIGHT)]
    assert biased.look == int(Look.STRAIGHT)


def test_tilting_the_head_after_calibrating_stops_input_until_it_comes_back():
    s = Session()
    s.pipeline.config.head_moved_deg = 3.0
    s.calibrate()
    reference = s.pipeline.calibrator.calibration.pitch_deg
    assert reference is not None
    for _ in range(30):
        result = s.frame(Look.STRAIGHT, head_nod_px=30)
    assert "HEAD_MOVED" in result["issues"] and result["head_tilt_deg"] > 3
    assert s.select(Look.UP, head_nod_px=30) is None              # nothing is typed while the head is tilted
    for _ in range(30):
        result = s.frame(Look.STRAIGHT)
    assert "HEAD_MOVED" not in result["issues"]
    assert s.select(Look.UP) == "up"


def test_the_moved_head_guard_is_off_during_calibration():
    s = Session()
    s.pipeline.config.head_moved_deg = 3.0
    s.calibrate()
    s.pipeline.start_calibration(s.t)
    for _ in range(30):
        result = s.frame(Look.STRAIGHT, head_nod_px=30)
    assert QualityIssue.HEAD_MOVED.name not in result["issues"]


class IndexedDetector:
    """Serves the faces recorded with the frames, in order, as bench.replay's cached detector does."""

    def __init__(self, faces):
        self.faces = faces
        self.index = 0

    def detect(self, bgr):
        return self.faces[self.index]


def test_a_recorded_session_replays_to_the_same_report(tmp_path):
    import cv2

    s = Session()
    recorder = SessionRecorder(1, str(tmp_path))
    faces = []
    original = s.frame

    def recorded_frame(look, **pose):
        result = original(look, **pose)
        image, _ = render(look, pupil_follow=s.follow, seed=s.seed, **pose)
        ok, jpeg = cv2.imencode(".png", image)   # lossless, so the replay sees identical pixels
        faces.append(s.detector.face)
        recorder.frame(jpeg.tobytes(), s.t, result)
        return result

    s.frame = recorded_frame
    recorder.control("calibrate", s.t)
    s.calibrate()
    recorder.control("look_test", s.t, order=[look.label for look in SHORT])
    report, _, _ = run_look_test(s)
    assert not recorder.recording                     # the finished test closes the recording

    folder = next(tmp_path.iterdir())
    events = [json.loads(line) for line in (folder / "events.jsonl").read_text().splitlines()]
    assert events[0]["control"] == "calibrate" and events[-1]["report"] == report
    replayed = replay(folder, events, IndexedDetector(faces), PipelineConfig(), fixed_sensitivity=False)
    assert replayed == report


def test_recording_is_off_without_a_folder(monkeypatch):
    monkeypatch.delenv("EYELAB_RECORD_DIR", raising=False)
    recorder = SessionRecorder(1)
    recorder.control("calibrate", 0)
    recorder.frame(b"x", 0, {})
    assert not recorder.enabled and not recorder.recording
