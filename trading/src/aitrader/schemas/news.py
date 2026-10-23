"""News analysis and news impact payloads.

External text is untrusted. These schemas carry only structured fields extracted from it,
plus verbatim excerpts used as evidence. No field here is ever interpreted as an instruction.
"""

from __future__ import annotations

from datetime import timedelta
from enum import StrEnum
from typing import Annotated, Literal

from pydantic import Field, model_validator

from aitrader.schemas.base import (
    EvidenceKind,
    EvidenceRef,
    Instrument,
    Probability,
    Score,
    ShortText,
    StrictModel,
    UtcDatetime,
)


class SourceTier(StrEnum):
    """Source priority; lower tiers get lower credibility weight."""

    EXCHANGE_FILING = "exchange_filing"
    CENTRAL_BANK_GOVT = "central_bank_govt"
    NEWSWIRE = "newswire"
    TRANSCRIPT = "transcript"
    SOCIAL = "social"


class EventType(StrEnum):
    EARNINGS = "earnings"
    GUIDANCE = "guidance"
    M_AND_A = "m_and_a"
    CAPITAL_RAISE = "capital_raise"
    BUYBACK_DIVIDEND = "buyback_dividend"
    MANAGEMENT_CHANGE = "management_change"
    REGULATORY = "regulatory"
    LEGAL = "legal"
    RATING_CHANGE = "rating_change"
    ORDER_WIN = "order_win"
    PROMOTER_ACTIVITY = "promoter_activity"
    MONETARY_POLICY = "monetary_policy"
    MACRO_DATA = "macro_data"
    FISCAL_POLICY = "fiscal_policy"
    GEOPOLITICAL = "geopolitical"
    OTHER = "other"


class NewsClaim(StrictModel):
    """One extracted fact. A claim without document evidence is dropped upstream."""

    summary: ShortText
    evidence: EvidenceRef

    @model_validator(mode="after")
    def _must_cite_document(self) -> NewsClaim:
        if self.evidence.kind is not EvidenceKind.DOCUMENT:
            raise ValueError("news claims must cite a source document")
        return self


class NewsEvent(StrictModel):
    kind: Literal["news_event"] = "news_event"
    event_id: Annotated[str, Field(min_length=1, max_length=100)]
    event_type: EventType
    source_tier: SourceTier
    first_published_at: UtcDatetime
    instruments: tuple[Instrument, ...] = ()
    sectors: tuple[ShortText, ...] = ()
    related_instruments: tuple[Instrument, ...] = ()  # suppliers, customers, competitors
    sentiment: Score
    novelty: Probability  # 0 = fully priced in / repeat, 1 = genuinely new
    materiality: Probability
    credibility: Probability
    half_life: timedelta
    claims: Annotated[tuple[NewsClaim, ...], Field(min_length=1)]
    duplicate_document_ids: tuple[str, ...] = ()
    extraction: Literal["llm", "rules"] = "rules"  # which path produced the scored fields

    @model_validator(mode="after")
    def _positive_half_life(self) -> NewsEvent:
        if self.half_life <= timedelta(0):
            raise ValueError("half_life must be positive")
        return self


class EventRiskFlag(StrictModel):
    kind: Literal["event_risk_flag"] = "event_risk_flag"
    flag_id: Annotated[str, Field(min_length=1, max_length=100)]
    event_type: EventType
    instrument: Instrument | None = None  # None = market-wide
    window_start: UtcDatetime
    window_end: UtcDatetime
    block_new_entries: bool
    exposure_multiplier: Annotated[float, Field(ge=0.0, le=1.0)] = 1.0
    reason: ShortText

    @model_validator(mode="after")
    def _window(self) -> EventRiskFlag:
        if self.window_end <= self.window_start:
            raise ValueError("window_end must be after window_start")
        return self


class ReactionWindow(StrEnum):
    M5 = "5m"
    H1 = "1h"
    D1 = "1d"
    D5 = "5d"


class AbnormalReturn(StrictModel):
    window: ReactionWindow
    abnormal_return: float
    t_stat: float | None = None
    expected_model: Literal["market", "sector", "beta"]


class ContagionOrder(StrEnum):
    FIRST = "first"  # the company
    SECOND = "second"  # peers, suppliers, customers
    INDEX = "index"
    CROSS_ASSET = "cross_asset"
    DERIVATIVES = "derivatives"


class ContagionLink(StrictModel):
    order: ContagionOrder
    target: Annotated[str, Field(min_length=1, max_length=100)]  # instrument key or asset name
    expected_impact_bps: float
    confidence: Probability


class NewsImpact(StrictModel):
    kind: Literal["news_impact"] = "news_impact"
    event_id: Annotated[str, Field(min_length=1, max_length=100)]
    abnormal_returns: tuple[AbnormalReturn, ...] = ()
    volume_shock_z: float | None = None
    volatility_shock_z: float | None = None
    absorption_minutes: Annotated[float, Field(ge=0)] | None = None
    overreaction_probability: Probability | None = None
    contagion: tuple[ContagionLink, ...] = ()
    # Overlapping events are flagged instead of forcing a single attribution.
    overlapping_event_ids: tuple[str, ...] = ()
    library_sample_size: Annotated[int, Field(ge=0)] = 0
    stress_scenario_id: str | None = None
