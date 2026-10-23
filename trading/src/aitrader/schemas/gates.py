"""Gate-layer payloads: liquidity plan and risk-to-reward verdict."""

from __future__ import annotations

from enum import StrEnum
from typing import Annotated, Literal
from uuid import UUID

from pydantic import Field, model_validator

from aitrader.schemas.base import Instrument, Probability, StrictModel
from aitrader.schemas.signals import SetupType


class ExecAlgo(StrEnum):
    MARKETABLE_LIMIT = "marketable_limit"
    TWAP = "twap"
    VWAP = "vwap"
    POV = "pov"
    ICEBERG = "iceberg"


class LiquidityRegime(StrEnum):
    NORMAL = "normal"
    THIN = "thin"
    STRESSED = "stressed"
    CIRCUIT_LOCKED = "circuit_locked"
    EXPIRY_DAY = "expiry_day"


class LiquidityPlan(StrictModel):
    kind: Literal["liquidity_plan"] = "liquidity_plan"
    decision_id: UUID
    instrument: Instrument
    liquidity_score: Probability
    regime: LiquidityRegime
    adv_shares: Annotated[float, Field(ge=0)]
    spread_bps: Annotated[float, Field(ge=0)]
    # Hard constraint: no downstream component may exceed it.
    max_quantity: Annotated[int, Field(ge=0)]
    freeze_quantity: Annotated[int, Field(ge=1)] | None = None
    algo: ExecAlgo
    participation_rate: Annotated[float, Field(gt=0.0, le=0.25)]
    horizon_minutes: Annotated[int, Field(ge=1)]
    est_impact_bps: Annotated[float, Field(ge=0)]
    days_to_liquidate: Annotated[float, Field(ge=0)]


class RRRejection(StrEnum):
    NEGATIVE_EV = "negative_ev"
    BELOW_MIN_RR = "below_min_rr"
    STOP_INSIDE_NOISE = "stop_inside_noise"
    EVENT_RISK = "event_risk"
    GAP_RISK = "gap_risk"
    MISSING_LEVELS = "missing_levels"


class RRVerdict(StrictModel):
    kind: Literal["rr_verdict"] = "rr_verdict"
    decision_id: UUID
    instrument: Instrument
    setup: SetupType
    passed: bool
    p_win: Probability
    p_win_source: Literal["historical_setup", "monte_carlo", "option_implied", "barrier_model", "blend"]
    reward_per_unit: float
    risk_per_unit: Annotated[float, Field(gt=0)]
    costs_per_unit: Annotated[float, Field(ge=0)]
    rr_after_costs: float
    expected_value_per_unit: float
    stop_distance_atr: Annotated[float, Field(ge=0)]
    rejections: tuple[RRRejection, ...] = ()

    @model_validator(mode="after")
    def _verdict_consistency(self) -> RRVerdict:
        if self.passed and self.rejections:
            raise ValueError("a passed verdict cannot carry rejections")
        if not self.passed and not self.rejections:
            raise ValueError("a failed verdict must give at least one rejection reason")
        if self.passed and self.expected_value_per_unit <= 0:
            raise ValueError("negative-EV trades can never pass")
        return self
