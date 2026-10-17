"""
The Calibration Coach: an agent that gets a user from "calibrated" to "every look is recognised reliably".

Perceive (OpenCV 5 measurements of the camera, the templates and live self-tests) -> decide (Claude on Amazon
Bedrock, or the deterministic rule policy as baseline and fallback) -> act (instruct the user, re-record one look,
ask the caregiver, propose a setting change for approval) -> perceive again. Every step is traced.
"""

from .runner import CoachRunner, CoachResult  # noqa: F401
