"""A scripted LLM client and a pinned config for tests. No network."""

from __future__ import annotations

import json
from collections.abc import Callable
from typing import Any

from aitrader.config import load_system_config
from aitrader.config.settings import LlmConfig
from aitrader.llm.client import LlmError, LlmRequest, LlmResponse


def pinned_config(**overrides: Any) -> LlmConfig:
    data = load_system_config().llm.model_dump()
    data["models"] = {"reasoning": "test-model-r", "extraction": "test-model-x"}
    data["pricing_usd_per_mtok"] = {
        "test-model-r": {"input": 4.0, "output": 20.0},
        "test-model-x": {"input": 1.0, "output": 5.0},
    }
    data.update(overrides)
    return LlmConfig.model_validate(data)


class FakeClient:
    """Returns scripted responses; a callable script can inspect the request."""

    def __init__(self, script: list[Any] | Callable[[LlmRequest], Any]) -> None:
        self.script = script
        self.requests: list[LlmRequest] = []

    def complete(self, request: LlmRequest) -> LlmResponse:
        self.requests.append(request)
        item = self.script(request) if callable(self.script) else self.script.pop(0)
        if isinstance(item, Exception):
            raise item
        if isinstance(item, LlmResponse):
            return item
        text = item if isinstance(item, str) else json.dumps(item)
        return LlmResponse(
            text=text, model=request.model, stop_reason="end_turn", input_tokens=1000, output_tokens=200
        )


__all__ = ["FakeClient", "LlmError", "pinned_config"]
