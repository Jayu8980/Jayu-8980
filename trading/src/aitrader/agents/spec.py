"""Agent contract: every agent declares its responsibility, typed I/O, tools, failure
behaviour and performance hook. The registry test checks these against the bus authority
table so an agent cannot be wired to publish where it has no authority."""

from __future__ import annotations

from enum import StrEnum
from typing import Annotated

from pydantic import Field

from aitrader.schemas.base import AgentId, StrictModel
from aitrader.schemas.envelope import Topic


class AgentKind(StrEnum):
    DETERMINISTIC = "deterministic"  # pure code, no LLM
    LLM = "llm"  # LLM judgment over tool outputs; never does arithmetic itself
    HYBRID = "hybrid"  # deterministic pipeline with an LLM extraction/judgment step


class FailureMode(StrEnum):
    SKIP = "skip"  # cycle proceeds without this agent; limits tighten
    FALLBACK = "fallback"  # deterministic fallback produces the output
    FREEZE = "freeze"  # new orders freeze until recovery (safety-critical agents)
    BLOCK_TRADE = "block_trade"  # the trade in flight is dropped, nothing else changes


class AgentSpec(StrictModel):
    agent_id: AgentId
    kind: AgentKind
    responsibility: Annotated[str, Field(min_length=10, max_length=600)]
    consumes: tuple[Topic, ...]
    publishes: tuple[Topic, ...]
    tools: tuple[str, ...] = ()  # names in the institutional tool registry
    timeout_key: str | None = None  # key in system.yaml timeouts_seconds
    failure_mode: FailureMode
    failure_behaviour: Annotated[str, Field(min_length=10, max_length=600)]
    performance_metrics: tuple[str, ...]  # recorded by the attribution hook
