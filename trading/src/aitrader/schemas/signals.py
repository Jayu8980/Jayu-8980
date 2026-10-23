"""Analyst-layer payloads: chart and smart-money signals.

Every analyst signal carries the common fields required by the communication design:
instrument, signal (directional score), confidence, horizon and evidence links.
"""

from __future__ import annotations

from enum import StrEnum
from typing import Annotated, Literal

from pydantic import Field, model_validator

from aitrader.schemas.base import (
    EvidenceRef,
    Horizon,
    Instrument,
    Price,
    Probability,
    Score,
    ShortText,
    StrictModel,
    Timeframe,
    UtcDatetime,
)

Evidence = Annotated[tuple[EvidenceRef, ...], Field(min_length=1)]


class SignalCore(StrictModel):
    instrument: Instrument
    score: Score  # -1 strongly bearish .. +1 strongly bullish
    confidence: Probability
    horizon: Horizon
    valid_until: UtcDatetime
    evidence: Evidence


class StructureState(StrEnum):
    UPTREND = "uptrend"  # HH/HL
    DOWNTREND = "downtrend"  # LH/LL
    RANGE = "range"
    BREAKOUT = "breakout"
    BREAKDOWN = "breakdown"
    FAILED_BREAKOUT = "failed_breakout"
    FAILED_BREAKDOWN = "failed_breakdown"


class TimeframeReading(StrictModel):
    timeframe: Timeframe
    structure: StructureState
    trend_score: Score
    confidence: Probability


class SetupType(StrEnum):
    BREAKOUT = "breakout"
    PULLBACK = "pullback"
    MEAN_REVERSION = "mean_reversion"
    RANGE = "range"
    NONE = "none"


class VolatilityRegime(StrEnum):
    COMPRESSED = "compressed"
    NORMAL = "normal"
    EXPANDED = "expanded"
    EXTREME = "extreme"


DIRECTIONAL_TIMEFRAMES = frozenset({Timeframe.MN1, Timeframe.W1, Timeframe.D1})


class ChartSignal(SignalCore):
    kind: Literal["chart_signal"] = "chart_signal"
    readings: Annotated[tuple[TimeframeReading, ...], Field(min_length=1)]
    # Agreement of the directional timeframes, computed by a deterministic tool.
    alignment_score: Score
    # Conflicts are listed explicitly, never averaged away.
    conflicts: tuple[ShortText, ...] = ()
    setup: SetupType
    volatility_regime: VolatilityRegime
    atr: Annotated[float, Field(gt=0)]
    entry_low: Price | None = None
    entry_high: Price | None = None
    structural_stop: Price | None = None
    targets: tuple[Price, ...] = ()
    # Context computed alongside the setup.
    relative_strength: dict[int, float] = Field(default_factory=dict)  # lookback -> excess return vs index
    volume_point_of_control: Price | None = None  # highest-volume price over the daily window
    unfilled_gaps: Annotated[int, Field(ge=0)] = 0

    @model_validator(mode="after")
    def _check_levels(self) -> ChartSignal:
        timeframes = [r.timeframe for r in self.readings]
        if len(timeframes) != len(set(timeframes)):
            raise ValueError("one reading per timeframe")
        if self.setup is not SetupType.NONE:
            low, high = self.entry_low, self.entry_high
            if low is None or high is None or self.structural_stop is None or not self.targets:
                raise ValueError("a classified setup needs entry zone, structural stop and targets")
            if low > high:
                raise ValueError("entry_low must not exceed entry_high")
        return self


class SmartMoneySignal(SignalCore):
    kind: Literal["smart_money_signal"] = "smart_money_signal"
    accumulation_score: Score  # -1 distribution .. +1 accumulation
    crowding: Probability  # 1 = extreme one-sided positioning
    flow_persistence_days: Annotated[int, Field(ge=0)]
    divergence_alert: bool
    components: tuple[ShortText, ...] = ()  # e.g. "FII index futures long build-up 4d"
    # Smart Money Concepts patterns stay flagged as hypotheses until validated out of sample.
    smc_patterns_validated: bool = False
