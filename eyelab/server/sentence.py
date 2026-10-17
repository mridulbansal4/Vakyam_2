"""
Keywords + the question asked -> one first-person sentence, with Claude on Amazon Bedrock.

Same task, prompt and examples as the phone's on-device Gemma (GemmaManager.promptAndExamples), and the same
never-silent rule: whatever happens (no credentials, timeout, refusal, an echo of the keywords), the user gets a
complete sentence from fallback_sentence().
"""

from __future__ import annotations

import logging
import re
import time
from dataclasses import dataclass
from typing import Callable

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
    source: str   # "claude" or "fallback"
    ms: int
    reason: str = ""


class SentenceService:
    def __init__(self, region: str, model: str, timeout_s: float, client_factory: Callable | None = None):
        self.region = region
        self.model = model
        self.timeout_s = timeout_s
        self._client_factory = client_factory
        self._client = None

    def _get_client(self):
        if self._client is None:
            if self._client_factory is not None:
                self._client = self._client_factory()
            else:
                from anthropic import AnthropicBedrockMantle
                self._client = AnthropicBedrockMantle(aws_region=self.region)
        return self._client

    def _messages(self, keywords: list[str], question: str) -> list[dict]:
        messages = []
        for user, assistant in EXAMPLES:
            messages += [{"role": "user", "content": user}, {"role": "assistant", "content": assistant}]
        messages.append({"role": "user", "content": f"Keywords: {', '.join(keywords)}. Context: {question}"})
        return messages

    def generate(self, keywords: list[str], question: str) -> Sentence:
        """Blocking; call it from a worker thread."""
        started = time.perf_counter()

        def done(text: str, source: str, reason: str = "") -> Sentence:
            return Sentence(text, source, round((time.perf_counter() - started) * 1000), reason)

        if not keywords:
            return done(fallback_sentence(keywords), "fallback", "no keywords")
        try:
            import anthropic
            client = self._get_client().with_options(timeout=self.timeout_s, max_retries=1)
            response = client.messages.create(
                model=self.model,
                max_tokens=1024,
                system=SYSTEM_PROMPT,
                messages=self._messages(keywords, question[:200]),
                output_config={"effort": "low"},  # a one-sentence rewrite: low effort keeps latency down
            )
        except Exception as e:  # noqa: BLE001 - every failure becomes a spoken fallback, logged by kind below
            reason = _failure_kind(e)
            log.warning("sentence fallback: %s", reason)
            return done(fallback_sentence(keywords), "fallback", reason)

        if response.stop_reason == "refusal":
            return done(fallback_sentence(keywords), "fallback", "refusal")
        text = tidy("".join(b.text for b in response.content if b.type == "text"))
        if not acceptable(text, keywords):
            return done(fallback_sentence(keywords), "fallback", "rejected output")
        return done(text, "claude")


def _failure_kind(e: Exception) -> str:
    try:
        import anthropic
    except ImportError:
        return "anthropic SDK not installed"
    if isinstance(e, anthropic.APITimeoutError):
        return "timeout"
    if isinstance(e, anthropic.RateLimitError):
        return "rate limited"
    if isinstance(e, anthropic.APIStatusError):
        return f"HTTP {e.status_code}"
    if isinstance(e, anthropic.APIConnectionError):
        return "connection error"
    return type(e).__name__  # e.g. missing AWS credentials or region
