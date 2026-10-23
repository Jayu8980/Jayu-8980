"""Provider-neutral LLM client contract."""

from __future__ import annotations

from typing import Annotated, Any, Literal, Protocol

from pydantic import Field

from aitrader.schemas.base import StrictModel


class LlmRequest(StrictModel):
    model: Annotated[str, Field(min_length=1)]
    system: str
    user: str
    json_schema: dict[str, Any]  # the response must be one JSON object matching this schema
    max_tokens: Annotated[int, Field(ge=1)]
    effort: Literal["low", "medium", "high", "xhigh", "max"]


class LlmResponse(StrictModel):
    text: str
    model: str
    stop_reason: str | None
    input_tokens: Annotated[int, Field(ge=0)]
    output_tokens: Annotated[int, Field(ge=0)]
    request_id: str | None = None


class LlmError(RuntimeError):
    """Any failure to obtain a usable response: transport, API error, refusal, truncation or
    budget. Agents treat it as an agent failure and fall back deterministically."""


class LlmClient(Protocol):
    def complete(self, request: LlmRequest) -> LlmResponse: ...
