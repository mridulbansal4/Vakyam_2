"""
Evaluate the Calibration Coach on simulated faults.

    python coach_eval.py                      # rule policy, every scenario
    python coach_eval.py --policy claude      # Claude on Bedrock (falls back to rules if unreachable)
    python coach_eval.py --scenario weak_up dark_room

Each run: calibrate a drawn face under the scenario's conditions, inject the fault, run the coach, then measure the
ground truth independently of the coach (every choosable look held and tested, and the resting look checked for
misreads). A run is correct when the coach's outcome agrees with that ground truth: "ready" exactly when the looks
really work. Writes results.json (with every step's trace) and results.md to --out.
"""

from __future__ import annotations

import argparse
import asyncio
import json
import os
import pathlib
import sys
import time

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))

from coach.claude import ClaudePolicy  # noqa: E402
from coach.rules import RulePolicy  # noqa: E402
from coach.runner import CoachRunner  # noqa: E402
from simulation.coach_env import SCENARIOS, SimEnvironment  # noqa: E402


def make_policy(name: str, region: str, model: str):
    if name == "rules":
        return RulePolicy(), None

    def client():
        from anthropic import AnthropicBedrockMantle
        return AnthropicBedrockMantle(aws_region=region)

    return ClaudePolicy(client, model), RulePolicy()


async def run_one(scenario, policy_name: str, region: str, model: str, seed: int) -> dict:
    env = SimEnvironment(scenario, seed=seed)
    env.calibrate()
    policy, fallback = make_policy(policy_name, region, model)
    started = time.perf_counter()
    result = await CoachRunner(env, policy, fallback).run()
    wall = time.perf_counter() - started
    truth = env.ground_truth()
    tools = [step["tool"] for step in result.trace]
    return {
        "scenario": scenario.name, "description": scenario.description, "policy": policy_name,
        "outcome": result.outcome, "designed_outcome": scenario.expected,
        "ground_truth_ok": truth["ok"], "correct": (result.outcome == "ready") == truth["ok"],
        "steps": result.steps, "simulated_seconds": round(env.t / 1000, 1), "wall_seconds": round(wall, 1),
        "approvals_requested": env.approvals_asked, "caregiver_requests": env.caregiver_asks,
        "recalibrations": tools.count("recalibrate_look"), "policies_used": result.policies,
        "fallback_reason": result.fallback_reason, "summary": result.summary, "ground_truth": truth,
        "tools": tools, "trace": result.trace,
    }


def markdown(rows: list[dict]) -> str:
    lines = ["| Scenario | Policy | Outcome | Ground truth | Correct | Steps | Sim. time | Approvals | Caregiver | "
             "Re-records |", "|---|---|---|---|---|---|---|---|---|---|"]
    for r in rows:
        policy = r["policy"] + (" → rules" if len(r["policies_used"]) > 1 else "")
        lines.append(f"| {r['scenario']} | {policy} | {r['outcome']} | {'works' if r['ground_truth_ok'] else 'fails'} "
                     f"| {'yes' if r['correct'] else '**no**'} | {r['steps']} | {r['simulated_seconds']} s "
                     f"| {r['approvals_requested']} | {r['caregiver_requests']} | {r['recalibrations']} |")
    n = len(rows)
    correct = sum(r["correct"] for r in rows)
    lines += ["", f"**{correct}/{n} correct.** Mean {sum(r['steps'] for r in rows) / n:.1f} steps and "
                  f"{sum(r['simulated_seconds'] for r in rows) / n:.0f} s of simulated time per run."]
    return "\n".join(lines)


async def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--policy", choices=["rules", "claude", "both"], default="rules")
    parser.add_argument("--scenario", nargs="*", help="scenario names (default: all)")
    parser.add_argument("--seed", type=int, default=1)
    parser.add_argument("--out", default=str(pathlib.Path(__file__).resolve().parent / "eval_results" / "coach"))
    parser.add_argument("--region", default=os.environ.get("EYELAB_BEDROCK_REGION", "us-east-1"))
    parser.add_argument("--model", default=os.environ.get("EYELAB_BEDROCK_MODEL", "anthropic.claude-opus-5-5"))
    args = parser.parse_args()

    scenarios = [s for s in SCENARIOS if not args.scenario or s.name in args.scenario]
    policies = ["rules", "claude"] if args.policy == "both" else [args.policy]
    rows = []
    for policy in policies:
        for scenario in scenarios:
            row = await run_one(scenario, policy, args.region, args.model, args.seed)
            rows.append(row)
            print(f"{row['scenario']:24s} {policy:6s} {row['outcome']:10s} truth={'works' if row['ground_truth_ok'] else 'fails':5s} "
                  f"correct={row['correct']} steps={row['steps']} {' > '.join(row['tools'])}", flush=True)

    out = pathlib.Path(args.out)
    out.mkdir(parents=True, exist_ok=True)
    suffix = "" if args.policy == "rules" and not args.scenario else f"-{args.policy}"
    (out / f"results{suffix}.json").write_text(json.dumps(rows, indent=1), encoding="utf-8")
    (out / f"results{suffix}.md").write_text(markdown(rows) + "\n", encoding="utf-8")
    print("\n" + markdown(rows))


if __name__ == "__main__":
    asyncio.run(main())
