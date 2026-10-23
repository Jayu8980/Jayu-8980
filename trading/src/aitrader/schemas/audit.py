"""Audit records. The audit log is append-only and hash-chained so tampering is detectable
and any decision can be replayed from the exact inputs, prompts and model versions used."""

from __future__ import annotations

from typing import Annotated, Any, Literal
from uuid import UUID

from pydantic import Field

from aitrader.schemas.base import AgentId, StrictModel, UtcDatetime
from aitrader.schemas.risk import HexDigest

GENESIS_HASH = "0" * 64


class LlmCallRecord(StrictModel):
    kind: Literal["llm_call"] = "llm_call"
    call_id: UUID
    agent_id: AgentId
    cycle_id: UUID
    provider: Annotated[str, Field(min_length=1, max_length=40)]
    model: Annotated[str, Field(min_length=1, max_length=100)]  # pinned id, never an alias
    prompt_id: Annotated[str, Field(min_length=1, max_length=100)]
    prompt_version: Annotated[str, Field(min_length=1, max_length=40)]
    prompt_sha256: HexDigest
    rendered_prompt: str  # full prompt as sent, including untrusted-data blocks
    temperature: Annotated[float, Field(ge=0.0, le=2.0)]
    seed: int | None = None
    response_text: str
    parsed_output_message_id: UUID | None = None  # None when output failed validation
    input_tokens: Annotated[int, Field(ge=0)]
    output_tokens: Annotated[int, Field(ge=0)]
    cost_usd: Annotated[float, Field(ge=0)]
    started_at: UtcDatetime
    finished_at: UtcDatetime


class ToolCallRecord(StrictModel):
    kind: Literal["tool_call"] = "tool_call"
    call_id: UUID
    agent_id: AgentId
    cycle_id: UUID
    tool: Annotated[str, Field(min_length=1, max_length=100)]
    tool_version: Annotated[str, Field(min_length=1, max_length=40)]
    inputs: dict[str, Any]
    output: dict[str, Any] | None = None
    error: str | None = None
    started_at: UtcDatetime
    finished_at: UtcDatetime


class AuditRecord(StrictModel):
    sequence: Annotated[int, Field(ge=0)]
    recorded_at: UtcDatetime
    record_type: Literal[
        "envelope", "llm_call", "tool_call", "config_snapshot", "human_action", "ingest_batch"
    ]
    body_json: str  # canonical JSON of the recorded object
    body_sha256: HexDigest
    prev_hash: HexDigest
    record_hash: HexDigest  # sha256(prev_hash + body_sha256 + sequence + recorded_at)
