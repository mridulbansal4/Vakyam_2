"""The Eye Lab service end to end over its WebSocket, with a drawn face and a stand-in for Claude."""

import io
import json
from dataclasses import replace
from types import SimpleNamespace

import cv2
import pytest
from fastapi.testclient import TestClient

from eyecore.looks import CALIBRATION_ORDER, Look
from server.app import create_app
from server.config import Settings
from server.metrics import SessionStats
from server.sentence import SentenceService, acceptable, clean_keywords, fallback_sentence
from simulation.face import FakeDetector, render


class FakeClaude:
    """Answers like the Messages API; records what it was sent."""

    def __init__(self, text="I would like some cold water, please.", stop_reason="end_turn", error=None):
        self.text, self.stop_reason, self.error, self.calls = text, stop_reason, error, []
        self.messages = self

    def with_options(self, **options):
        self.options = options
        return self

    def create(self, **kwargs):
        self.calls.append(kwargs)
        if self.error:
            raise self.error
        return SimpleNamespace(stop_reason=self.stop_reason, content=[SimpleNamespace(type="text", text=self.text)])


def settings(**overrides):
    base = Settings.from_env()
    return replace(base, max_fps=1000, debug_crops_every=0, **overrides)


def jpeg(look, seed):
    image, face = render(look, seed=seed)
    ok, data = cv2.imencode(".jpg", image, [cv2.IMWRITE_JPEG_QUALITY, 92])
    return data.tobytes(), face


def make_client(claude=None, **overrides):
    detector = FakeDetector()
    sentences = SentenceService("us-east-1", "anthropic.claude-opus-5-5", 5, client_factory=lambda: claude or FakeClaude())
    app = create_app(settings(**overrides), detector_factory=lambda: detector, sentences=sentences, speech=None)
    return TestClient(app), detector


def test_health_and_page():
    client, _ = make_client()
    health = client.get("/healthz").json()
    assert health["ok"] and health["opencv"].startswith("5.")
    assert health["cool"] is False and health["opencv_file"].endswith(".py")  # the PyPI wheel, not COOL
    page = client.get("/")
    assert page.status_code == 200 and "Speak without hands." in page.text
    assert client.get("/static/app.js").status_code == 200 and client.get("/static/keyboard.js").status_code == 200
    words = client.get("/words/words")
    assert words.status_code == 200 and words.text.split()[:3] == ["the", "be", "and"]
    assert client.get("/words/bigrams").status_code == 200
    assert client.get("/words/..%2Fsecrets").status_code == 404 and client.get("/words/passwd").status_code == 404


def test_calibrate_then_select_a_look_over_the_socket():
    client, detector = make_client()
    with client.websocket_connect("/ws") as ws:
        assert ws.receive_json()["type"] == "hello"
        ws.send_text(json.dumps({"type": "calibrate"}))
        assert ws.receive_json()["type"] == "calibration"
        seed = 0
        for look in CALIBRATION_ORDER:  # the server runs on wall-clock time: ~1.1 s a step
            for _ in range(2000):
                seed += 1
                data, detector.face = jpeg(look, seed)
                ws.send_bytes(data)
                msg = ws.receive_json()
                if msg.get("skipped"):  # sent within the frame-rate guard's gap
                    continue
                if msg["calibration"]["captured"] == look.label:
                    break
        assert msg["calibrated"]
        selected = None
        for _ in range(400):
            seed += 1
            data, detector.face = jpeg(Look.RIGHT_UP, seed)
            ws.send_bytes(data)
            msg = ws.receive_json()
            if msg.get("skipped"):
                continue
            selected = selected or msg["selected"]
            if selected:
                break
        assert selected == "right-up"
        assert msg["look"]["name"] == "right-up" and msg["face"] is not None


def test_sentence_comes_from_claude_with_the_question_as_context():
    claude = FakeClaude()
    client, _ = make_client(claude)
    with client.websocket_connect("/ws") as ws:
        ws.receive_json()
        ws.send_text(json.dumps({"type": "sentence", "id": 7, "keywords": ["water", "cold"],
                                 "question": "What would you like?"}))
        reply = ws.receive_json()
    assert reply == {"type": "sentence", "text": "I would like some cold water, please.", "source": "claude",
                     "ms": reply["ms"], "id": 7}  # the id lets the page ignore a reply to an older word
    call = claude.calls[0]
    assert call["model"] == "anthropic.claude-opus-5-5"
    assert call["messages"][-1]["content"] == "Keywords: water, cold. Context: What would you like?"
    assert call["output_config"] == {"effort": "low"}


@pytest.mark.parametrize("claude,reason", [
    (FakeClaude(error=RuntimeError("no credentials")), "RuntimeError"),
    (FakeClaude(stop_reason="refusal"), "refusal"),
    (FakeClaude(text="water"), "rejected output"),
    (FakeClaude(text="water cold"), "rejected output"),
])
def test_the_user_is_never_left_without_a_sentence(claude, reason):
    service = SentenceService("us-east-1", "m", 5, client_factory=lambda: claude)
    s = service.generate(["water", "cold"], "")
    assert s.source == "fallback" and s.reason == reason
    assert s.text == "I would like some water, please. I am feeling cold, please help."


def test_keywords_are_cleaned():
    assert clean_keywords(["water", " tea ", "<script>", "x" * 40, 5, "ice-cream", "a", "b"]) == \
        ["water", "tea", "ice-cream", "a"]
    assert clean_keywords("water") == []


def test_fallback_sentences_match_the_phone():
    assert fallback_sentence(["water"]) == "I would like some water, please."
    assert fallback_sentence(["yes"]) == "Yes."
    assert fallback_sentence(["music"]) == "I want music, please."
    assert fallback_sentence(["family", "doctor"]) == "I want family and doctor, please."
    assert fallback_sentence([]) == "Yes."


def test_guard_rails():
    assert acceptable("I would like some tea.", ["tea"])
    assert not acceptable("tea", ["tea"])
    assert not acceptable("Tea please", ["tea", "please"])
    assert not acceptable("मुझे चाय चाहिए", ["chai"])


def test_bad_input_is_answered_not_fatal():
    client, _ = make_client(max_frame_bytes=100)
    with client.websocket_connect("/ws") as ws:
        ws.receive_json()
        ws.send_bytes(b"x" * 101)
        assert ws.receive_json()["error"] == "frame_too_large"
        ws.send_bytes(b"not a jpeg")
        assert ws.receive_json()["error"] == "bad_frame"
        ws.send_text("{nope")
        assert ws.receive_json()["error"] == "bad_json"
        ws.send_text(json.dumps({"type": "launch"}))
        assert ws.receive_json()["error"] == "unknown_message"
        ws.send_text(json.dumps({"type": "ping"}))
        assert ws.receive_json()["type"] == "pong"


def test_sessions_are_capped():
    client, _ = make_client(max_sessions=1)
    with client.websocket_connect("/ws") as first:
        first.receive_json()
        with client.websocket_connect("/ws") as second:
            assert second.receive_json()["error"] == "busy"


def test_session_metrics_are_cloudwatch_emf():
    stats = SessionStats()
    stats.frame({"ms": 4.0, "face": {}, "selected": "left", "issues": []})
    stats.frame({"ms": 6.0, "face": None, "selected": None, "issues": ["TOO_DARK"]})
    out = io.StringIO()
    record = stats.summary(out)
    assert json.loads(out.getvalue()) == record
    names = {m["Name"] for m in record["_aws"]["CloudWatchMetrics"][0]["Metrics"]}
    assert {"Frames", "FrameMsAvg", "Selections", "QualityIssueRate"} <= names
    assert record["Frames"] == 2 and record["Selections"] == 1 and record["QualityIssueRate"] == 0.5


def test_coach_runs_live_over_the_socket():
    """Real time: the rule coach checks the camera, the templates and every look while frames keep flowing."""
    client, detector = make_client(coach_policy="rules")
    labels = {look.label: look for look in Look}
    with client.websocket_connect("/ws") as ws:
        ws.receive_json()
        ws.send_text(json.dumps({"type": "coach_start"}))
        assert ws.receive_json()["error"] == "not_calibrated"
        ws.send_text(json.dumps({"type": "calibrate"}))
        ws.receive_json()
        seed = 0
        for look in CALIBRATION_ORDER:
            for _ in range(2000):
                seed += 1
                data, detector.face = jpeg(look, seed)
                ws.send_bytes(data)
                msg = ws.receive_json()
                if msg.get("calibration", {}).get("captured") == look.label:
                    break
        ws.send_text(json.dumps({"type": "coach_start"}))
        target, tools, done = Look.STRAIGHT, [], None
        while done is None:
            seed += 1
            data, detector.face = jpeg(target, seed)
            ws.send_bytes(data)
            while True:  # coach events arrive between frame replies
                msg = ws.receive_json()
                if msg["type"] != "coach":
                    break
                if msg["event"] == "instruct":
                    target = labels.get(msg.get("look"), Look.STRAIGHT)
                elif msg["event"] == "instruct_done":
                    target = Look.STRAIGHT
                elif msg["event"] == "step":
                    tools.append(msg["tool"])
                elif msg["event"] == "done":
                    done = msg
                    break
    assert done["outcome"] == "ready", done
    assert tools[0] == "inspect_camera" and tools[-1] == "finish"
    assert tools.count("self_test") == 6
