"""Feedback-layer payloads: TCA, attribution, calibration and agent health."""

from __future__ import annotations

from enum import StrEnum
from typing import Annotated, Literal
from uuid import UUID

from pydantic import Field

from aitrader.schemas.base import AgentId, Instrument, Probability, StrictModel, UtcDatetime
from aitrader.schemas.regime import RegimeLabel


class TcaResult(StrictModel):
    kind: Literal["tca_result"] = "tca_result"
    instruction_id: UUID
    instrument: Instrument
    filled_quantity: Annotated[int, Field(ge=0)]
    avg_price: float
    arrival_price: float
    vwap_benchmark: float | None = None
    realised_slippage_bps: float
    estimated_impact_bps: float
    estimation_error_bps: float  # realised - estimated, feeds impact-model recalibration


class AgentScore(StrictModel):
    kind: Literal["agent_score"] = "agent_score"
    agent_id: AgentId
    regime: RegimeLabel
    window_start: UtcDatetime
    window_end: UtcDatetime
    sample_size: Annotated[int, Field(ge=0)]
    hit_rate: Probability | None = None
    brier_score: Annotated[float, Field(ge=0.0, le=1.0)] | None = None
    information_coefficient: Annotated[float, Field(ge=-1.0, le=1.0)] | None = None
    credibility: Probability  # shrunk toward a prior when sample_size is small


class HealthStatus(StrEnum):
    HEALTHY = "healthy"
    DEGRADED = "degraded"
    TIMED_OUT = "timed_out"
    FAILED = "failed"
    SILENT = "silent"


class AgentHeartbeat(StrictModel):
    kind: Literal["agent_heartbeat"] = "agent_heartbeat"
    agent_id: AgentId
    status: HealthStatus
    last_output_at: UtcDatetime | None = None
    latency_ms: Annotated[float, Field(ge=0)] | None = None
    llm_cost_usd: Annotated[float, Field(ge=0)] = 0.0
