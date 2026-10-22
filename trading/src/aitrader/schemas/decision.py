"""Boss agent (CIO) decision memo."""

from __future__ import annotations

from enum import StrEnum
from typing import Annotated, Literal
from uuid import UUID

from pydantic import Field, model_validator

from aitrader.schemas.base import (
    AgentId,
    EvidenceRef,
    Horizon,
    Instrument,
    Price,
    Probability,
    ShortText,
    StrictModel,
    UtcDatetime,
)


class Action(StrEnum):
    BUY = "BUY"
    SELL = "SELL"
    HOLD = "HOLD"
    REDUCE = "REDUCE"
    HEDGE = "HEDGE"
    WAIT = "WAIT"


TRADING_ACTIONS = frozenset({Action.BUY, Action.SELL, Action.REDUCE, Action.HEDGE})


class InvalidationKind(StrEnum):
    PRICE_CLOSE_BELOW = "price_close_below"
    PRICE_CLOSE_ABOVE = "price_close_above"
    TIME_STOP = "time_stop"
    EVENT = "event"
    REGIME_CHANGE = "regime_change"


class Invalidation(StrictModel):
    condition: InvalidationKind
    level: Price | None = None
    deadline: UtcDatetime | None = None
    description: ShortText

    @model_validator(mode="after")
    def _needs_parameter(self) -> Invalidation:
        price_kinds = {InvalidationKind.PRICE_CLOSE_BELOW, InvalidationKind.PRICE_CLOSE_ABOVE}
        if self.condition in price_kinds and self.level is None:
            raise ValueError("price invalidation needs a level")
        if self.condition is InvalidationKind.TIME_STOP and self.deadline is None:
            raise ValueError("time stop needs a deadline")
        return self


class AgentWeight(StrictModel):
    """Credibility weight used by the boss, as computed by the attribution tool."""

    agent_id: AgentId
    weight: Probability
    signal_message_id: UUID | None = None  # None = agent missing this cycle


class BossDecision(StrictModel):
    kind: Literal["boss_decision"] = "boss_decision"
    decision_id: UUID
    action: Action
    instrument: Instrument
    horizon: Horizon
    conviction: Probability
    # Fraction of the per-trade risk budget the boss wants to use. The boss never computes
    # quantities; deterministic sizing turns this into a quantity and the gates may cut it.
    risk_budget_fraction: Annotated[float, Field(ge=0.0, le=1.0)]
    open_short: bool = False  # SELL opens a short only when this is explicitly true
    key_reasons: Annotated[tuple[ShortText, ...], Field(min_length=1, max_length=5)]
    main_risk: ShortText
    critical_disagreement: ShortText | None = None
    invalidation: Invalidation | None = None
    agent_weights: tuple[AgentWeight, ...] = ()
    evidence: tuple[EvidenceRef, ...] = ()
    revision_of: UUID | None = None  # set when revising after a failed gate
    revision_number: Annotated[int, Field(ge=0)] = 0
    used_fallback: bool = False  # deterministic rule set produced this decision
    requires_human_approval: bool = False

    @model_validator(mode="after")
    def _consistency(self) -> BossDecision:
        if self.action in TRADING_ACTIONS:
            if self.invalidation is None:
                raise ValueError("trading decisions need an explicit invalidation condition")
            if self.risk_budget_fraction <= 0 and self.action is not Action.REDUCE:
                raise ValueError("trading decisions need a positive risk budget fraction")
        else:
            if self.risk_budget_fraction != 0:
                raise ValueError(f"{self.action} must not request risk budget")
        if self.open_short and self.action is not Action.SELL:
            raise ValueError("open_short is only valid with SELL")
        if (self.revision_of is None) != (self.revision_number == 0):
            raise ValueError("revision_of and revision_number must be set together")
        return self
