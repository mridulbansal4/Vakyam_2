"""
The rule policy: a fixed procedure over the same tools. It is the baseline the Gemini policy is evaluated against,
and the fallback whenever the model cannot be reached.
"""

from __future__ import annotations

import math

from eyecore.looks import Look

from .environment import CHOOSABLE

RECOGNISED = 0.7       # a look passes its self test when recognised in at least this share of frames
ISSUE_SHARE = 0.5      # a camera issue counts when present in at least half the frames
USER_FIXES = {
    "TURNED_AWAY": "Please turn your face towards the screen.",
    "TOO_FAR": "Please come a little closer to the camera.",
    "TILTED": "Please keep your head level with the camera.",
    "BLURRY": "Please hold your head still for a moment.",
}
CAREGIVER_FIXES = {
    "NO_FACE": "The camera cannot see a face. Please move the screen so it faces the user.",
    "TOO_DARK": "The face is too dark. Please turn on a light in front of the user.",
    "BACKLIT": "There is bright light behind the user. Please close the curtain or move the screen.",
    "OVEREXPOSED": "Light is shining straight onto the face. Please dim it or move it aside.",
}
FURTHER = {
    Look.LEFT: "Look further left, right at the left edge of the screen.",
    Look.RIGHT: "Look further right, right at the right edge of the screen.",
    Look.UP: "Look further up, right at the top edge of the screen.",
    Look.LEFT_UP: "Look right into the top left corner.",
    Look.RIGHT_UP: "Look right into the top right corner.",
    Look.CLOSED: "Close your eyes fully until you hear the beep.",
    Look.STRAIGHT: "Look at the very centre of the screen.",
}
ORDER = list(USER_FIXES) + list(CAREGIVER_FIXES)


def _look(label: str) -> Look:
    return next(look for look in Look if look.label == label)


def camera_problem(report: dict) -> str | None:
    if report.get("face_found_rate", 0) < 0.5:
        return "NO_FACE"
    present = [issue for issue, share in report.get("issues", {}).items() if share >= ISSUE_SHARE]
    present.sort(key=lambda i: ORDER.index(i) if i in ORDER else len(ORDER))
    return present[0] if present else None


class RulePolicy:
    name = "rules"

    async def run(self, coach) -> None:
        # 1. Make the picture usable. An issue nobody can fix is noted, not fatal: the self tests decide whether
        #    the looks still work in this picture. Only no face, or a head turned away, stops coaching here.
        report = (await coach.call("inspect_camera", {}, "Check the camera first.")).content
        unfixed: list[str] = []
        for _ in range(3):
            issue = camera_problem(report)
            if issue is None or issue in unfixed:
                break
            if issue in USER_FIXES:
                result = await coach.call("instruct_user", {"message": USER_FIXES[issue], "wait_seconds": 4},
                                          f"Camera shows {issue}; the user can fix this.")
                report = result.content["camera_after"]
            else:
                result = await coach.call("ask_caregiver", {"message": CAREGIVER_FIXES[issue]},
                                          f"Camera shows {issue}; only the caregiver can fix this.")
                if result.content["answer"] == "done":
                    report = result.content["camera_after"]
                else:
                    unfixed.append(issue)
        issue = camera_problem(report)
        if issue in ("NO_FACE", "TURNED_AWAY"):
            return await self._finish(coach, "needs_help", f"The camera still shows {issue}; the looks cannot be "
                                                           "tested until the user faces the camera.")
        if issue is not None and issue not in unfixed:
            unfixed.append(issue)

        # 2. Separate look-alike templates
        attempts: dict[Look, int] = {}
        for _ in range(3):
            templates = (await coach.call("check_templates", {}, "Look for templates that are too alike.")).content
            if not templates["confusable"]:
                break
            pair = [_look(label) for label in templates["confusable"][0]["looks"]]
            look = next((p for p in pair if p != Look.STRAIGHT), pair[-1])
            if attempts.get(look, 0) >= 2:
                break
            attempts[look] = attempts.get(look, 0) + 1
            await coach.call("recalibrate_look", {"look": look.label, "instruction": FURTHER[look]},
                             f"{pair[0].label} and {pair[1].label} are only {templates['confusable'][0]['distance']} "
                             f"apart; re-record {look.label} further out.")

        # 3. Prove every look works, and repair the ones that do not
        failing = {}
        for look in CHOOSABLE:
            report = (await coach.call("self_test", {"look": look.label}, f"Self test {look.label}.")).content
            if report["recognised_rate"] < RECOGNISED:
                failing[look] = report
        # The right look wins but the threshold rejects it: one proposal for all of them, never one per look
        threshold = coach.env.sensitivity / 50
        rejected = {look: r for look, r in failing.items()
                    if r.get("best_match_rate", 0) >= 0.8 and (r.get("median_error") or 0) > threshold}
        if rejected:
            worst_look, worst = max(rejected.items(), key=lambda kv: kv[1]["median_error"])
            value = min(45, max(10, coach.env.sensitivity + 2, math.ceil(worst["median_error"] * 50 * 1.25)))
            names = ", ".join(look.label for look in rejected)
            reason = (f"{names}: the right look is the closest match but rejected. The worst, {worst_look.label}, "
                      f"has error {worst['median_error']} against a limit of {threshold:.2f}.")
            approved = (await coach.call("propose_sensitivity", {"value": value, "reason": reason},
                                         "The right looks win but the threshold rejects them.")).content["approved"]
            if approved:
                for look in rejected:
                    retest = (await coach.call("self_test", {"look": look.label},
                                               f"Check {look.label} again.")).content
                    if retest["recognised_rate"] >= RECOGNISED:
                        failing.pop(look)
                resting = (await coach.call("self_test", {"look": Look.STRAIGHT.label},
                                            "A looser threshold must not turn resting into a selection.")).content
                if resting["recognised_rate"] < RECOGNISED:
                    return await self._finish(coach, "needs_help", "With the looser threshold the resting look is "
                                                                   "misread as a selection. Recalibrate with a "
                                                                   "caregiver.")

        # A different look wins: re-record the failing look once
        for look, report in list(failing.items()):
            if look in rejected:
                continue
            await coach.call("recalibrate_look", {"look": look.label, "instruction": FURTHER[look]},
                             f"{look.label} is recognised only {report['recognised_rate']:.0%} of the time.")
            retest = (await coach.call("self_test", {"look": look.label}, f"Check {look.label} again.")).content
            if retest["recognised_rate"] >= RECOGNISED:
                failing.pop(look)

        note = f" The picture still shows {', '.join(unfixed)}; fix it when you can." if unfixed else ""
        if failing:
            names = ", ".join(look.label for look in failing)
            return await self._finish(coach, "needs_help", f"These looks are still not recognised reliably: {names}. "
                                                           "Recalibrate with a caregiver, or check the camera "
                                                           "position." + note)
        await self._finish(coach, "ready", "Every look passed its self test." + note)

    @staticmethod
    async def _finish(coach, outcome: str, summary: str) -> None:
        await coach.call("finish", {"outcome": outcome, "summary": summary}, "Done.")
