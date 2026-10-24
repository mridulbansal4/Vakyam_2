"""Settings from environment variables, so the same image runs locally and on ECS."""

from __future__ import annotations

import os
import pathlib
from dataclasses import dataclass

ROOT = pathlib.Path(__file__).resolve().parent.parent


def _default_assets() -> str:
    bundled = ROOT / "assets"                                  # inside the container
    in_repo = ROOT.parent / "app/src/main/assets"               # running from the repo, or the COOL bundle
    return str(bundled if bundled.exists() else in_repo)


def _default_model() -> str:
    bundled = ROOT / "models/face_detection_yunet_2023mar.onnx"           # inside the container
    in_repo = ROOT.parent / "app/src/main/assets/face_detection_yunet_2023mar.onnx"  # running from the repo
    return str(bundled if bundled.exists() else in_repo)


@dataclass(frozen=True)
class Settings:
    model_path: str
    assets_dir: str
    aws_region: str
    gemini_model: str
    gemini_secret: str
    sentence_timeout_s: float
    polly_enabled: bool
    polly_voice: str
    max_sessions: int
    max_fps: float
    max_frame_bytes: int
    session_idle_s: float
    debug_crops_every: int
    coach_policy: str
    coach_effort: str

    @classmethod
    def from_env(cls) -> "Settings":
        e = os.environ.get
        return cls(
            model_path=e("EYELAB_MODEL_PATH", _default_model()),
            assets_dir=e("EYELAB_ASSETS_DIR", _default_assets()),
            aws_region=e("AWS_REGION", "us-east-1"),
            gemini_model=e("EYELAB_GEMINI_MODEL", "gemini-3.8-flash"),
            gemini_secret=e("EYELAB_GEMINI_SECRET", ""),  # Secrets Manager name; GEMINI_API_KEY wins when set
            sentence_timeout_s=float(e("EYELAB_SENTENCE_TIMEOUT_S", "8")),
            polly_enabled=e("EYELAB_POLLY", "0") == "1",
            polly_voice=e("EYELAB_POLLY_VOICE", "Joanna"),
            max_sessions=int(e("EYELAB_MAX_SESSIONS", "20")),
            max_fps=float(e("EYELAB_MAX_FPS", "15")),
            max_frame_bytes=int(e("EYELAB_MAX_FRAME_BYTES", str(200 * 1024))),
            session_idle_s=float(e("EYELAB_SESSION_IDLE_S", "120")),
            debug_crops_every=int(e("EYELAB_DEBUG_CROPS_EVERY", "3")),
            coach_policy=e("EYELAB_COACH_POLICY", "gemini"),
            coach_effort=e("EYELAB_COACH_EFFORT", "medium"),
        )
