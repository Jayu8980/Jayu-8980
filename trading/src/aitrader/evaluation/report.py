"""Evaluation report model."""

from __future__ import annotations

from datetime import date

from pydantic import Field

from aitrader.schemas.base import AgentId, StrictModel


class AgentEvaluation(StrictModel):
    agent_id: AgentId
    start: date
    end: date
    n: int  # scored observations
    metrics: dict[str, float | None]
    baselines: dict[str, float | None]  # what a naive forecaster scores on the same sample
    by_group: dict[str, dict[str, float | None]] = Field(default_factory=dict)
    notes: tuple[str, ...] = ()
