"""
The Gemini API key and client, shared by the sentence writer and the Calibration Coach.

The key never lives in the image, the template or the repository. Locally it comes from GEMINI_API_KEY; on AWS the
stack passes only the name of a Secrets Manager secret (EYELAB_GEMINI_SECRET), and the key is read from there with
the task's own role the first time it is needed.
"""

from __future__ import annotations

import logging
import os
import threading
import time

log = logging.getLogger("eyelab.llm")


class GeminiKey:
    RECHECK_S = 300  # a missing key is looked for again after this long, not on every sentence

    def __init__(self, secret_name: str = "", region: str = "us-east-1"):
        self.secret_name = secret_name
        self.region = region
        self._key: str | None = None
        self._missing_until = 0.0
        self._lock = threading.Lock()

    def get(self) -> str | None:
        """The key, or None when there is none; never raises."""
        with self._lock:
            if self._key:
                return self._key
            now = time.monotonic()
            if now < self._missing_until:
                return None
            key = os.environ.get("GEMINI_API_KEY", "").strip() or self._from_secrets_manager()
            if key:
                self._key = key
            else:
                self._missing_until = now + self.RECHECK_S
            return self._key

    def _from_secrets_manager(self) -> str:
        if not self.secret_name:
            return ""
        try:
            import boto3
            value = boto3.client("secretsmanager", region_name=self.region).get_secret_value(
                SecretId=self.secret_name)["SecretString"]
        except Exception as e:  # noqa: BLE001 - no credentials, no secret, no permission: the fallbacks take over
            log.warning("Gemini key unavailable from Secrets Manager: %s", type(e).__name__)
            return ""
        return value.strip()


GEMINI_MIN_TIMEOUT_S = 10  # the API answers 400 to any request with a shorter deadline


def gemini_client(key: str, timeout_s: float):
    from google import genai
    from google.genai import types
    timeout_s = max(timeout_s, GEMINI_MIN_TIMEOUT_S)
    return genai.Client(api_key=key, http_options=types.HttpOptions(timeout=int(timeout_s * 1000)))


BLOCKED_FINISH = {"SAFETY", "PROHIBITED_CONTENT", "BLOCKLIST", "SPII", "RECITATION"}


def first_content(response):
    """The first candidate's content, or None."""
    candidates = getattr(response, "candidates", None) or []
    return getattr(candidates[0], "content", None) if candidates else None


def finish_reason(response) -> str:
    candidates = getattr(response, "candidates", None) or []
    reason = getattr(candidates[0], "finish_reason", None) if candidates else None
    return getattr(reason, "name", str(reason))


def blocked(response) -> bool:
    """Gemini's refusals: the prompt was blocked, or the answer stopped for a safety reason."""
    feedback = getattr(response, "prompt_feedback", None)
    if feedback is not None and getattr(feedback, "block_reason", None):
        return True
    return finish_reason(response) in BLOCKED_FINISH


def answer_text(response) -> str:
    """The visible text of the first candidate, without thought summaries."""
    parts = getattr(first_content(response), "parts", None) or []
    return "".join(p.text for p in parts if getattr(p, "text", None) and not getattr(p, "thought", False))


def failure_kind(e: Exception, prefix: str = "") -> str:
    try:
        from google.genai import errors
    except ImportError:
        return "google-genai SDK not installed"
    if isinstance(e, errors.APIError):
        return f"{prefix}HTTP {e.code}" if e.code != 429 else f"{prefix}rate limited"
    if isinstance(e, TimeoutError) or "timeout" in type(e).__name__.lower():
        return f"{prefix}timeout"
    return type(e).__name__
