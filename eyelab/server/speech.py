"""Amazon Polly text-to-speech. Optional: without it the browser's own voice speaks the sentence."""

from __future__ import annotations

import base64
import logging

log = logging.getLogger("eyelab.speech")


class PollySpeech:
    def __init__(self, region: str, voice: str):
        self.region = region
        self.voice = voice
        self._client = None

    def synthesize(self, text: str) -> str | None:
        """An audio/mpeg data URL, or None if Polly is unavailable."""
        try:
            if self._client is None:
                import boto3
                self._client = boto3.client("polly", region_name=self.region)
            audio = self._client.synthesize_speech(Text=text, OutputFormat="mp3", VoiceId=self.voice,
                                                   Engine="neural")["AudioStream"].read()
        except Exception as e:  # noqa: BLE001 - the browser voice takes over
            log.warning("polly unavailable: %s", type(e).__name__)
            return None
        return "data:audio/mpeg;base64," + base64.b64encode(audio).decode()
