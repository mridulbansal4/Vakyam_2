"""The Calibration Coach: tools, runner, the Gemini loop (scripted), and real scenarios on the simulator."""

import asyncio
import json
from types import SimpleNamespace

import pytest

from coach.gemini import FUNCTIONS, GeminiPolicy
from coach.rules import RulePolicy, camera_problem
from coach.runner import CoachRunner
from coach.tools import TOOLS, execute
from eyecore.looks import Look
from simulation.coach_env import SCENARIOS, SimEnvironment


def run(coro):
    return asyncio.run(coro)


class FakeEnv:
    """Canned perceptions; records what the coach did."""

    def __init__(self, camera_reports=None, approve=True):
        self.sensitivity = 20
        self.camera_reports = list(camera_reports or [{"face_found_rate": 1.0, "issues": {}}])
        self.approve = approve
        self.did = []

    async def camera(self):
        self.did.append("camera")
        return self.camera_reports.pop(0) if len(self.camera_reports) > 1 else self.camera_reports[0]

    async def templates(self):
        return {"closest_pairs": [], "confusable": [], "threshold": 0.15}

    async def self_test(self, look):
        self.did.append(f"test:{look.label}")
        return {"look": look.label, "frames": 70, "recognised_rate": 1.0, "best_match_rate": 1.0,
                "median_error": 0.01, "median_margin": 0.5, "confused_with": {}}

    async def instruct(self, message, wait_s):
        self.did.append(f"say:{message}")

    async def recalibrate(self, look, instruction):
        return {"captured": True, "status": "captured"}

    async def ask_caregiver(self, message):
        return "done"

    async def request_approval(self, change, reason):
        self.did.append("approval")
        return self.approve

    async def apply_sensitivity(self, value):
        self.sensitivity = value

    async def report(self, event):
        pass


# ---------- tools ----------

def test_tool_definitions_are_well_formed():
    names = [t["name"] for t in TOOLS]
    assert len(names) == len(set(names)) == 8
    for t in TOOLS:
        assert t["description"] and t["input_schema"]["type"] == "object"


@pytest.mark.parametrize("name,args,error", [
    ("self_test", {"look": "sideways"}, "look must be one of"),
    ("instruct_user", {"message": "x" * 200}, "at most 160"),
    ("instruct_user", {"message": "  "}, "non-empty"),
    ("instruct_user", {"message": "Hi", "wait_seconds": 30}, "between 2 and 8"),
    ("propose_sensitivity", {"value": 200, "reason": "r"}, "integer from 10 to 45"),
    ("finish", {"outcome": "great", "summary": "s"}, "ready"),
    ("launch_rocket", {}, "unknown tool"),
])
def test_bad_tool_input_is_an_error_result_not_an_exception(name, args, error):
    result = run(execute(FakeEnv(), name, args))
    assert result.is_error and error in result.content["error"]


def test_sensitivity_changes_only_with_approval():
    env = FakeEnv(approve=False)
    result = run(execute(env, "propose_sensitivity", {"value": 30, "reason": "closed eyes rejected"}))
    assert result.content == {"approved": False, "sensitivity": 20} and env.sensitivity == 20
    env = FakeEnv(approve=True)
    result = run(execute(env, "propose_sensitivity", {"value": 30, "reason": "closed eyes rejected"}))
    assert result.content == {"approved": True, "sensitivity": 30}


def test_instruct_returns_a_fresh_camera_report():
    env = FakeEnv([{"face_found_rate": 1.0, "issues": {"TURNED_AWAY": 0.9}}, {"face_found_rate": 1.0, "issues": {}}])
    result = run(execute(env, "instruct_user", {"message": "Please face the screen."}))
    assert result.content["camera_after"]["issues"] == {"TURNED_AWAY": 0.9}  # first report served after the instruction


def test_camera_problem_priorities():
    assert camera_problem({"face_found_rate": 0.2, "issues": {}}) == "NO_FACE"
    assert camera_problem({"face_found_rate": 1.0, "issues": {"TOO_DARK": 0.9, "TURNED_AWAY": 0.6}}) == "TURNED_AWAY"
    assert camera_problem({"face_found_rate": 1.0, "issues": {"BLURRY": 0.3}}) is None


# ---------- runner ----------

class Looper:
    name = "looper"

    async def run(self, coach):
        while True:
            await coach.call("inspect_camera", {})


class Broken:
    name = "broken"

    async def run(self, coach):
        await coach.call("inspect_camera", {})
        from coach.runner import PolicyFailed
        raise PolicyFailed("model timeout")


def test_step_limit_stops_a_policy_that_never_finishes():
    result = run(CoachRunner(FakeEnv(), Looper(), max_steps=5).run())
    assert result.outcome == "incomplete" and result.steps == 5


def test_a_failed_policy_hands_over_to_the_fallback():
    result = run(CoachRunner(FakeEnv(), Broken(), RulePolicy()).run())
    assert result.policies == ["broken", "rules"] and result.fallback_reason == "model timeout"
    assert result.outcome == "ready"
    assert result.trace[0]["policy"] == "broken" and result.trace[1]["policy"] == "rules"


def test_tools_after_finish_are_refused():
    env = FakeEnv()

    class FinishTwice:
        name = "x"

        async def run(self, coach):
            await coach.call("finish", {"outcome": "ready", "summary": "ok"})
            self.second = await coach.call("inspect_camera", {})

    policy = FinishTwice()
    run(CoachRunner(env, policy).run())
    assert policy.second.is_error


# ---------- Gemini policy, scripted ----------

class ScriptedGemini:
    """Stands in for Gemini: chooses its next tool from the last tool result, as the real model must."""

    def __init__(self, fail_at=None):
        self.requests, self.responses = [], []
        self.models = self
        self.fail_at = fail_at

    def generate_content(self, **kwargs):
        self.requests.append(kwargs)
        if self.fail_at is not None and len(self.requests) == self.fail_at:
            raise TimeoutError("simulated timeout")
        part = kwargs["contents"][-1]["parts"][0]
        if "text" in part:
            response = self._tool("inspect_camera", {}, "Start by checking the camera.")
        else:
            result = part["function_response"]["response"]
            if result.get("issues", {}).get("TURNED_AWAY", 0) >= 0.5:
                response = self._tool("instruct_user", {"message": "Please turn your face towards the screen."},
                                      "The camera shows the head turned away.")
            elif "instructed" in result or "issues" in result:
                response = self._tool("finish", {"outcome": "ready", "summary": "Camera fixed."},
                                      "The picture is fine now.")
            else:
                response = self._answer([SimpleNamespace(text="Done.", thought=False, function_call=None)])
        self.responses.append(response)
        return response

    @staticmethod
    def _answer(parts):
        return SimpleNamespace(prompt_feedback=None, candidates=[
            SimpleNamespace(finish_reason="STOP", content=SimpleNamespace(role="model", parts=parts))])

    @classmethod
    def _tool(cls, name, args, text):
        return cls._answer([
            SimpleNamespace(text="(thinking)", thought=True, function_call=None),
            SimpleNamespace(text=text, thought=False, function_call=None),
            SimpleNamespace(text=None, thought=False, function_call=SimpleNamespace(id=None, name=name, args=args))])


def test_gemini_loop_acts_on_the_opencv_result():
    env = FakeEnv([{"face_found_rate": 1.0, "issues": {"TURNED_AWAY": 0.9}}, {"face_found_rate": 1.0, "issues": {}}])
    gemini = ScriptedGemini()
    result = run(CoachRunner(env, GeminiPolicy(lambda: gemini, "gemini-3.8-flash"), RulePolicy()).run())
    assert [s["tool"] for s in result.trace] == ["inspect_camera", "instruct_user", "finish"]
    assert result.trace[1]["rationale"] == "The camera shows the head turned away."  # thoughts are not rationale
    assert result.outcome == "ready" and result.policies == ["gemini"]
    request = gemini.requests[1]
    assert request["model"] == "gemini-3.8-flash"
    assert request["config"]["tools"] == [{"function_declarations": FUNCTIONS}]
    assert [f["name"] for f in FUNCTIONS] == [t["name"] for t in TOOLS]
    assert request["config"]["thinking_config"] == {"thinking_level": "medium"}
    assert request["config"]["automatic_function_calling"] == {"disable": True}
    # append-only: the model's first turn is passed back exactly as returned (it carries thought signatures)
    assert request["contents"][1] is gemini.responses[0].candidates[0].content
    reply = request["contents"][2]["parts"][0]["function_response"]
    assert reply["name"] == "inspect_camera" and reply["response"]["issues"] == {"TURNED_AWAY": 0.9}


def test_gemini_failure_falls_back_to_rules_without_losing_progress():
    gemini = ScriptedGemini(fail_at=2)
    result = run(CoachRunner(FakeEnv(), GeminiPolicy(lambda: gemini, "m"), RulePolicy()).run())
    assert result.policies == ["gemini", "rules"] and result.fallback_reason == "model timeout"
    assert result.outcome == "ready" and result.trace[0]["policy"] == "gemini"


def test_no_credentials_falls_back_immediately():
    def no_client():
        raise LookupError("no Gemini API key")

    result = run(CoachRunner(FakeEnv(), GeminiPolicy(no_client, "m"), RulePolicy()).run())
    assert result.policies == ["gemini", "rules"] and "client unavailable" in result.fallback_reason


# ---------- the rule policy on real simulated scenarios ----------

@pytest.mark.parametrize("name", ["weak_up", "turned_away", "strict_threshold_denied", "no_face_unfixable"])
def test_rule_coach_agrees_with_the_ground_truth(name):
    scenario = next(s for s in SCENARIOS if s.name == name)
    env = SimEnvironment(scenario, seed=3)
    env.calibrate()
    result = run(CoachRunner(env, RulePolicy()).run())
    truth = env.ground_truth()
    assert result.outcome == scenario.expected
    assert (result.outcome == "ready") == truth["ok"]
    if name == "weak_up":
        assert "recalibrate_look" in [s["tool"] for s in result.trace]
        recal = next(s for s in result.trace if s["tool"] == "recalibrate_look")
        assert recal["input"]["look"] == Look.UP.label  # the measurement pointed at up, not straight
    if name == "no_face_unfixable":
        assert result.steps <= 4  # stops and says so, does not loop
