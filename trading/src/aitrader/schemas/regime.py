"""Market regime and outlook payloads (upcoming trend analysis agent)."""

from __future__ import annotations

import math
from enum import StrEnum
from typing import Annotated, Literal

from pydantic import Field, model_validator

from aitrader.schemas.base import EvidenceRef, Probability, Score, ShortText, StrictModel, UtcDatetime


class RegimeLabel(StrEnum):
    BULL_TREND = "bull_trend"
    BEAR_TREND = "bear_trend"
    RANGE = "range"
    HIGH_VOL_STRESS = "high_vol_stress"
    RECOVERY = "recovery"
    UNKNOWN = "unknown"  # used whenever the trend agent is unavailable


class Scenario(StrictModel):
    name: Annotated[str, Field(min_length=1, max_length=60)]
    probability: Probability
    evidence: Annotated[tuple[EvidenceRef, ...], Field(min_length=1)]


class SectorTilt(StrictModel):
    sector: Annotated[str, Field(min_length=1, max_length=60)]
    tilt: Score  # -1 underweight .. +1 overweight


class RegimeOutlook(StrictModel):
    kind: Literal["regime_outlook"] = "regime_outlook"
    regime: RegimeLabel
    confidence: Probability
    scenarios: Annotated[tuple[Scenario, ...], Field(min_length=2)]
    sector_tilts: tuple[SectorTilt, ...] = ()
    early_warnings: tuple[ShortText, ...] = ()
    # Target gross exposure multiplier; the risk agent rate-limits how fast it may change.
    exposure_multiplier: Annotated[float, Field(ge=0.0, le=1.0)]
    forecast_horizon_end: UtcDatetime

    @model_validator(mode="after")
    def _probabilities_sum_to_one(self) -> RegimeOutlook:
        total = math.fsum(s.probability for s in self.scenarios)
        if not math.isclose(total, 1.0, abs_tol=1e-6):
            raise ValueError(f"scenario probabilities must sum to 1, got {total}")
        names = [s.name for s in self.scenarios]
        if len(names) != len(set(names)):
            raise ValueError("scenario names must be unique")
        return self
