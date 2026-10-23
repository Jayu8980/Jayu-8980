"""Notification channels. Sending never raises: a failed notification must not interrupt
trading, so failures are counted and the last error kept for the dashboard and logs.

Messages go out as plain text (no Markdown or HTML parse mode), so instrument names or
alert details cannot inject formatting or links. The bot token is part of the Telegram URL
and is scrubbed from any error message that is kept.
"""

from __future__ import annotations

import json
import urllib.error
import urllib.parse
import urllib.request
from collections.abc import Callable
from typing import Protocol

from aitrader.config.settings import secret

TELEGRAM_LIMIT = 4096
# (url, form fields) -> parsed JSON response; raises like urlopen.
Transport = Callable[[str, dict[str, str]], dict[str, object]]


class Notifier(Protocol):
    def send(self, text: str) -> bool: ...


class MemoryNotifier:
    """Keeps messages (tests, or when no channel is configured)."""

    def __init__(self) -> None:
        self.messages: list[str] = []

    def send(self, text: str) -> bool:
        self.messages.append(text)
        return True


def _post(url: str, fields: dict[str, str], timeout: float = 10.0) -> dict[str, object]:
    data = urllib.parse.urlencode(fields).encode()
    with urllib.request.urlopen(urllib.request.Request(url, data=data, method="POST"), timeout=timeout) as r:
        body: dict[str, object] = json.loads(r.read())
        return body


class TelegramNotifier:
    def __init__(self, token: str, chat_id: str, transport: Transport = _post, retries: int = 2) -> None:
        self._token = token
        self._chat_id = chat_id
        self._transport = transport
        self._retries = retries
        self.failures = 0
        self.last_error: str | None = None

    @classmethod
    def from_env(cls, token_env: str, chat_id_env: str) -> TelegramNotifier:
        return cls(secret(token_env), secret(chat_id_env))

    def send(self, text: str) -> bool:
        if len(text) > TELEGRAM_LIMIT:
            text = text[: TELEGRAM_LIMIT - 1] + "…"
        url = f"https://api.telegram.org/bot{self._token}/sendMessage"
        fields = {"chat_id": self._chat_id, "text": text, "disable_web_page_preview": "true"}
        for _ in range(self._retries + 1):
            try:
                response = self._transport(url, fields)
            except (urllib.error.URLError, TimeoutError, OSError, ValueError) as exc:
                self.last_error = f"{type(exc).__name__}: {exc}".replace(self._token, "<token>")
                continue
            if response.get("ok") is True:
                return True
            self.last_error = str(response.get("description", "telegram refused the message"))
        self.failures += 1
        return False
