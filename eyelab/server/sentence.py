"""
Keywords + the question asked -> one first-person sentence, with Gemini.

Same task, prompt and examples as the phone's on-device model (GemmaManager.promptAndExamples), and the same
never-silent rule: whatever happens (no API key, timeout, a blocked answer, an echo of the keywords), the user gets a
complete sentence from fallback_sentence().
"""

from __future__ import annotations

import logging
import re
import time
from dataclasses import dataclass
from typing import Callable

from .llm import GeminiKey, answer_text, blocked, failure_kind, gemini_client

log = logging.getLogger("eyelab.sentence")

SYSTEM_PROMPT = (
    "You are an AI assistant for a person who cannot speak or move due to ALS/motor neuron disease. The user "
    "communicates by selecting keywords with their eyes. Your task is to generate ONE natural, grammatical, "
    "first-person English sentence using the provided keywords that answers the conversation context. "
    "CRITICAL: Always expand the keywords into a complete, natural, first-person sentence (e.g., 'I want water, "
    "please.'). NEVER output only the raw keyword or a single word. Reply with the sentence only, in English, "
    "without quotes or explanation."
)

EXAMPLES = [
    ("Keywords: chicken. Context: What do you want to eat for dinner", "I want to have chicken for dinner."),
    ("Keywords: hot, AC, two. Context: is the room temperature ok", "I am hot, can you turn the AC down by two degrees?"),
    ("Keywords: water, cold, please. Context: ", "Could you please bring me some cold water?"),
    ("Keywords: happy, thank you. Context: ", "I am happy, thank you so much."),
]

STANDALONE_REPLIES = {"yes", "no", "ok", "okay", "hello", "hi", "bye", "thanks", "sorry", "please"}
ENGLISH_REQUESTS = {
    "water": "I would like some water, please.",
    "food": "I would like something to eat, please.",
    "tea": "Could I please have some tea?",
    "coffee": "Could I please have some coffee?",
    "medicine": "I need to take my medicine, please.",
    "toilet": "I need to use the washroom, please.",
    "washroom": "I need to use the washroom, please.",
    "help": "Can someone please help me?",
    "pain": "I am in pain, please help me.",
    "cold": "I am feeling cold, please help.",
    "hot": "I am feeling hot, please adjust the temperature.",
    "hungry": "I am hungry, I would like something to eat.",
    "tired": "I am feeling tired and need to rest.",
}
KEYWORD = re.compile(r"^[A-Za-z][A-Za-z '\-]{0,23}$")


def clean_keywords(raw) -> list[str]:
    """At most four short keywords made of letters, spaces, apostrophes and hyphens; anything else is dropped."""
    if not isinstance(raw, list):
        return []
    return [k.strip() for k in raw if isinstance(k, str) and KEYWORD.match(k.strip())][:4]


def fallback_sentence(keywords: list[str]) -> str:
    """GemmaManager.fallbackSentence for English, extended to join several keywords."""
    seen = set()
    keywords = [k for k in keywords if not (k.lower() in seen or seen.add(k.lower()))]  # "tea tea" says it once
    if not keywords:
        return "Yes."
    if len(keywords) == 1:
        word = keywords[0].lower()
        if word in STANDALONE_REPLIES:
            return keywords[0].capitalize() + "."
        return ENGLISH_REQUESTS.get(word, f"I want {keywords[0]}, please.")
    known = [ENGLISH_REQUESTS[k.lower()] for k in keywords if k.lower() in ENGLISH_REQUESTS]
    if len(known) == len(keywords):
        return " ".join(known)
    return f"I want {', '.join(keywords[:-1])} and {keywords[-1]}, please."


def acceptable(sentence: str, keywords: list[str]) -> bool:
    """The phone's guard rails: not blank, not a single word, not just the keywords repeated, Latin script only."""
    words = re.findall(r"[A-Za-z']+", sentence)
    if len(words) < 2 or len(sentence) > 300:
        return False
    if any(ord(ch) > 0x2FF and not ch.isspace() and ch not in "‘’“”–—…" for ch in sentence):
        return False
    return {w.lower() for w in words} != {w.lower() for k in keywords for w in k.split()}


def tidy(text: str) -> str:
    line = text.strip().splitlines()[0] if text.strip() else ""
    return line.strip().strip('"“”').strip()


@dataclass(frozen=True)
class Sentence:
    text: str
    source: str   # "gemini" or "fallback"
    ms: int
    reason: str = ""


class SentenceService:
    def __init__(self, key: GeminiKey, model: str, timeout_s: float, client_factory: Callable | None = None):
        self.key = key
        self.model = model
        self.timeout_s = timeout_s
        self._client_factory = client_factory  # tests inject a stand-in; otherwise built from the key
        self._client = None

    def warm(self) -> None:
        """Read the key early (from Secrets Manager on AWS), so the first sentence does not wait for it."""
        if self._client_factory is None:
            self.key.get()

    def _get_client(self):
        """The client, or None when there is no key."""
        if self._client is None:
            if self._client_factory is not None:
                self._client = self._client_factory()
            elif (key := self.key.get()) is not None:
                self._client = gemini_client(key, self.timeout_s)
        return self._client

    @staticmethod
    def _contents(keywords: list[str], question: str) -> list[dict]:
        contents = []
        for user, model in EXAMPLES:
            contents += [{"role": "user", "parts": [{"text": user}]}, {"role": "model", "parts": [{"text": model}]}]
        contents.append({"role": "user", "parts": [{"text": f"Keywords: {', '.join(keywords)}. Context: {question}"}]})
        return contents

    def generate(self, keywords: list[str], question: str) -> Sentence:
        """Blocking; call it from a worker thread."""
        started = time.perf_counter()

        def done(text: str, source: str, reason: str = "") -> Sentence:
            return Sentence(text, source, round((time.perf_counter() - started) * 1000), reason)

        if not keywords:
            return done(fallback_sentence(keywords), "fallback", "no keywords")
        try:
            client = self._get_client()
            if client is None:
                return done(fallback_sentence(keywords), "fallback", "no Gemini API key")
            response = client.models.generate_content(
                model=self.model,
                contents=self._contents(keywords, question[:200]),
                config={
                    "system_instruction": SYSTEM_PROMPT,
                    "max_output_tokens": 2048,  # thinking counts towards it
                    "thinking_config": {"thinking_level": "low"},  # a one-sentence rewrite: low keeps latency down
                },
            )
        except Exception as e:  # noqa: BLE001 - every failure becomes a spoken fallback, logged by kind
            reason = failure_kind(e)
            log.warning("sentence fallback: %s", reason)
            return done(fallback_sentence(keywords), "fallback", reason)

        if blocked(response):
            return done(fallback_sentence(keywords), "fallback", "blocked")
        text = tidy(answer_text(response))
        if not acceptable(text, keywords):
            return done(fallback_sentence(keywords), "fallback", "rejected output")
        return done(text, "gemini")
