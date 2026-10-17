"""Runs one coaching session: a policy calls tools through here, so every step is limited, validated and traced."""

from __future__ import annotations

import time
from dataclasses import dataclass, field
from typing import Protocol

from .environment import CoachEnvironment
from .tools import ToolResult, execute


class StepLimit(Exception):
    pass


class PolicyFailed(Exception):
    """The policy's decision-maker failed (e.g. the model is unreachable); the runner falls back."""


class Policy(Protocol):
    name: str

    async def run(self, coach: "CoachRunner") -> None: ...


@dataclass
class CoachResult:
    outcome: str                      # "ready", "needs_help" or "incomplete"
    summary: str
    steps: int
    seconds: float
    policies: list[str]
    approvals_requested: int
    trace: list[dict] = field(default_factory=list)
    fallback_reason: str | None = None

    def to_json(self) -> dict:
        return {"outcome": self.outcome, "summary": self.summary, "steps": self.steps,
                "seconds": round(self.seconds, 1), "policies": self.policies,
                "approvals_requested": self.approvals_requested, "fallback_reason": self.fallback_reason,
                "trace": self.trace}


class CoachRunner:
    def __init__(self, env: CoachEnvironment, policy: Policy, fallback: Policy | None = None,
                 max_steps: int = 24, clock=time.monotonic):
        self.env = env
        self.policy = policy
        self.fallback = fallback
        self.max_steps = max_steps
        self.clock = clock
        self.steps = 0
        self.trace: list[dict] = []
        self.finished: dict | None = None
        self.approvals = 0
        self._active = policy.name

    async def call(self, name: str, args: dict, rationale: str = "") -> ToolResult:
        if self.finished is not None:
            return ToolResult({"error": "coaching has already finished"}, is_error=True)
        if self.steps >= self.max_steps:
            raise StepLimit()
        self.steps += 1
        started = self.clock()
        await self.env.report({"event": "step_start", "step": self.steps, "tool": name, "input": args,
                               "policy": self._active, "rationale": rationale})
        result = await execute(self.env, name, args)
        if name == "propose_sensitivity" and not result.is_error:
            self.approvals += 1
        entry = {"step": self.steps, "policy": self._active, "rationale": rationale, "tool": name, "input": args,
                 "result": result.content, "is_error": result.is_error,
                 "seconds": round(self.clock() - started, 2)}
        self.trace.append(entry)
        await self.env.report({"event": "step", **entry})
        if result.finished:
            self.finished = result.content
        return result

    async def run(self) -> CoachResult:
        started = self.clock()
        policies = [self.policy.name]
        fallback_reason = None
        try:
            try:
                await self.policy.run(self)
            except PolicyFailed as e:
                if self.fallback is None:
                    raise
                fallback_reason = str(e) or type(e).__name__
                self._active = self.fallback.name
                policies.append(self.fallback.name)
                await self.env.report({"event": "fallback", "from": self.policy.name, "to": self.fallback.name,
                                       "reason": fallback_reason})
                await self.fallback.run(self)
        except StepLimit:
            pass
        except PolicyFailed as e:
            fallback_reason = str(e)

        if self.finished is not None:
            outcome, summary = self.finished["outcome"], self.finished["summary"]
        elif self.steps >= self.max_steps:
            outcome, summary = "incomplete", f"Stopped after the {self.max_steps}-step limit."
        else:
            outcome, summary = "incomplete", "The coach stopped without finishing."
        result = CoachResult(outcome, summary, self.steps, self.clock() - started, policies, self.approvals,
                             self.trace, fallback_reason)
        await self.env.report({"event": "done", **{k: v for k, v in result.to_json().items() if k != "trace"}})
        return result
