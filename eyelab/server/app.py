"""
Eye Lab: the Vakyam eye pipeline as a web service.

One WebSocket per browser session. The browser sends webcam frames as JPEG binary messages, at most one in flight,
and control messages as JSON text; the server answers every frame with the pipeline's result. Frames are decoded,
processed in memory and dropped: nothing about the image is stored or logged.

    uvicorn server.app:app --host 0.0.0.0 --port 8080
"""

from __future__ import annotations

import asyncio
import json
import logging
import pathlib
import time
from typing import Callable

import cv2
import numpy as np
from fastapi import FastAPI, WebSocket, WebSocketDisconnect
from fastapi.responses import FileResponse, JSONResponse
from fastapi.staticfiles import StaticFiles
from starlette.concurrency import run_in_threadpool

from eyecore import EyePipeline, PipelineConfig, __version__
from eyecore.face import YuNetDetector

from coach.claude import ClaudePolicy
from coach.rules import RulePolicy
from coach.runner import CoachRunner

from .coach_session import SessionCoachEnv
from .config import Settings
from .metrics import SessionStats, emit
from .sentence import SentenceService, clean_keywords
from .speech import PollySpeech

log = logging.getLogger("eyelab")
STATIC = pathlib.Path(__file__).resolve().parent / "static"
CLOSE_TRY_LATER = 1013


def create_app(settings: Settings | None = None, detector_factory: Callable | None = None,
               sentences: SentenceService | None = None, speech: PollySpeech | None = None,
               coach_client_factory: Callable | None = None) -> FastAPI:
    settings = settings or Settings.from_env()
    detector_factory = detector_factory or (lambda: YuNetDetector(settings.model_path))
    sentences = sentences or SentenceService(settings.bedrock_region, settings.bedrock_model,
                                             settings.sentence_timeout_s)
    speech = speech if speech is not None else (
        PollySpeech(settings.bedrock_region, settings.polly_voice) if settings.polly_enabled else None)

    if coach_client_factory is None:
        def coach_client_factory():
            from anthropic import AnthropicBedrockMantle
            return AnthropicBedrockMantle(aws_region=settings.bedrock_region)

    def coach_policy():
        if settings.coach_policy == "rules":
            return RulePolicy(), None
        return (ClaudePolicy(coach_client_factory, settings.bedrock_model, effort=settings.coach_effort),
                RulePolicy())

    app = FastAPI(title="Vakyam Eye Lab", version=__version__, docs_url=None, redoc_url=None)
    app.state.active = 0
    app.state.settings = settings

    @app.get("/healthz")
    def healthz():
        return {"ok": True, "version": __version__, "sessions": app.state.active, "opencv": cv2.__version__}

    @app.get("/")
    def index():
        return FileResponse(STATIC / "index.html", headers={"Cache-Control": "no-cache"})

    @app.get("/config.json")
    def client_config():
        return JSONResponse({"maxFps": settings.max_fps, "polly": speech is not None})

    app.mount("/static", StaticFiles(directory=STATIC), name="static")

    @app.websocket("/ws")
    async def session(ws: WebSocket):
        await ws.accept()
        if app.state.active >= settings.max_sessions:
            await ws.send_json({"type": "error", "error": "busy", "message": "Eye Lab is full. Try again shortly."})
            await ws.close(CLOSE_TRY_LATER)
            return
        app.state.active += 1
        pipeline = EyePipeline(detector_factory(), PipelineConfig(debug_crops_every=settings.debug_crops_every))
        stats = SessionStats()
        send_lock = asyncio.Lock()

        async def send(payload: dict) -> None:  # the coach task and the frame loop both send
            async with send_lock:
                await ws.send_json(payload)

        coach = {"env": SessionCoachEnv(pipeline, send), "task": None}
        # The browser paces itself at max_fps; network and timer jitter can bunch two frames together, so the
        # server only refuses bursts above twice that rate.
        min_gap = 0.5 / settings.max_fps
        last_frame = 0.0
        try:
            await send({"type": "hello", "version": __version__, "opencv": cv2.__version__})
            while True:
                message = await asyncio.wait_for(ws.receive(), timeout=settings.session_idle_s)
                if message["type"] == "websocket.disconnect":
                    break
                if message.get("bytes") is not None:
                    data = message["bytes"]
                    now = time.monotonic()
                    if len(data) > settings.max_frame_bytes:
                        await send({"type": "error", "error": "frame_too_large"})
                        continue
                    if now - last_frame < min_gap:  # the client paces itself; this guards against one that doesn't
                        await send({"type": "frame", "skipped": True})
                        continue
                    last_frame = now
                    image = cv2.imdecode(np.frombuffer(data, np.uint8), cv2.IMREAD_COLOR)
                    if image is None:
                        await send({"type": "error", "error": "bad_frame"})
                        continue
                    try:
                        result = await run_in_threadpool(pipeline.process, image)
                    except Exception:  # noqa: BLE001 - one bad frame must not end the session
                        log.exception("frame failed")
                        await send({"type": "error", "error": "frame_failed"})
                        continue
                    stats.frame(result)
                    coach["env"].on_frame(result)
                    await send({"type": "frame", **result})
                elif message.get("text") is not None:
                    await handle_control(ws, message["text"], pipeline, stats, coach, send)
        except (WebSocketDisconnect, asyncio.TimeoutError):
            pass
        finally:
            if coach["task"] is not None:
                coach["task"].cancel()
            app.state.active -= 1
            stats.summary()

    async def run_coach(env: SessionCoachEnv) -> None:
        policy, fallback = coach_policy()
        try:
            result = await CoachRunner(env, policy, fallback).run()
        except Exception:  # noqa: BLE001 - the session carries on without the coach
            log.exception("coach failed")
            await env.report({"event": "done", "outcome": "incomplete", "summary": "The coach stopped on an error."})
            return
        emit({"CoachRuns": (1, "Count"), "CoachReady": (int(result.outcome == "ready"), "Count"),
              "CoachSteps": (result.steps, "Count"), "CoachSeconds": (round(result.seconds, 1), "Seconds"),
              "CoachApprovals": (result.approvals_requested, "Count"),
              "CoachFallback": (int(len(result.policies) > 1), "Count")},
             {"event": "coach_done", "outcome": result.outcome, "policies": result.policies,
              "fallback_reason": result.fallback_reason,
              "tools": [step["tool"] for step in result.trace]})

    async def handle_control(ws: WebSocket, text: str, pipeline: EyePipeline, stats: SessionStats, coach: dict,
                             send) -> None:
        try:
            msg = json.loads(text)
        except json.JSONDecodeError:
            await send({"type": "error", "error": "bad_json"})
            return
        kind = msg.get("type") if isinstance(msg, dict) else None
        if kind == "calibrate":
            stats.calibration_started = time.monotonic()
            await send({"type": "calibration", **pipeline.start_calibration(int(time.monotonic() * 1000))})
        elif kind == "sentence":
            keywords = clean_keywords(msg.get("keywords"))
            question = str(msg.get("question", ""))[:200]
            sentence = await run_in_threadpool(sentences.generate, keywords, question)
            stats.sentences += 1
            stats.sentence_fallbacks += sentence.source == "fallback"
            emit({"SentenceMs": (sentence.ms, "Milliseconds"),
                  "SentenceFallback": (int(sentence.source == "fallback"), "Count")},
                 {"event": "sentence", "source": sentence.source, "reason": sentence.reason,
                  "keywords": len(keywords)})
            reply = {"type": "sentence", "text": sentence.text, "source": sentence.source, "ms": sentence.ms}
            if speech is not None:
                reply["audio"] = await run_in_threadpool(speech.synthesize, sentence.text)
            await send(reply)
        elif kind == "coach_start":
            if not pipeline.calibrated:
                await send({"type": "error", "error": "not_calibrated", "message": "Calibrate before coaching."})
            elif coach["task"] is None or coach["task"].done():
                coach["task"] = asyncio.create_task(run_coach(coach["env"]))
        elif kind == "coach_answer":
            if not coach["env"].answer(str(msg.get("kind")), msg.get("value")):
                await send({"type": "error", "error": "no_question_pending"})
        elif kind == "coach_stop":
            if coach["task"] is not None:
                coach["task"].cancel()
                await send({"type": "coach", "event": "done", "outcome": "incomplete", "summary": "Stopped."})
        elif kind == "ping":
            await send({"type": "pong"})
        else:
            await send({"type": "error", "error": "unknown_message"})

    return app


logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(name)s %(message)s")
app = create_app()
