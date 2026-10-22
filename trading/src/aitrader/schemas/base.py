"""Base types shared by every message and tool schema.

Rules enforced here:
- Every model is immutable (frozen) and rejects unknown fields (no free text sneaking in).
- Every timestamp must be timezone-aware and is normalised to UTC.
- Collections are tuples so frozen models stay deeply immutable.
"""

from __future__ import annotations

from datetime import UTC, date, datetime
from decimal import Decimal
from enum import StrEnum
from typing import Annotated

from pydantic import AfterValidator, BaseModel, ConfigDict, Field, model_validator

SCHEMA_VERSION = "1.0.0"


class StrictModel(BaseModel):
    model_config = ConfigDict(frozen=True, extra="forbid", str_strip_whitespace=True)


def _require_utc(value: datetime) -> datetime:
    if value.tzinfo is None or value.tzinfo.utcoffset(value) is None:
        raise ValueError("timestamp must be timezone-aware")
    return value.astimezone(UTC)


UtcDatetime = Annotated[datetime, AfterValidator(_require_utc)]
Probability = Annotated[float, Field(ge=0.0, le=1.0)]
Score = Annotated[float, Field(ge=-1.0, le=1.0)]
Price = Annotated[Decimal, Field(gt=0, max_digits=18, decimal_places=4)]
ShortText = Annotated[str, Field(min_length=1, max_length=500)]


class AgentId(StrEnum):
    DATA = "data"
    CHART = "chart"
    NEWS = "news"
    NEWS_IMPACT = "news_impact"
    SMART_MONEY = "smart_money"
    TREND = "trend"
    BULL = "bull"
    BEAR = "bear"
    DEBATE = "debate"
    BOSS = "boss"
    BOSS_FALLBACK = "boss_fallback"
    LIQUIDITY = "liquidity"
    RISK_REWARD = "risk_reward"
    RISK = "risk"
    EXECUTION = "execution"
    ORCHESTRATOR = "orchestrator"
    TCA = "tca"
    ATTRIBUTION = "attribution"
    HUMAN = "human"


class Priority(StrEnum):
    INTERRUPT = "interrupt"  # risk alerts, kill switch: always processed first
    HIGH = "high"
    NORMAL = "normal"
    LOW = "low"


class Exchange(StrEnum):
    NSE = "NSE"
    BSE = "BSE"


class Segment(StrEnum):
    CASH = "cash"
    FNO = "fno"
    INDEX = "index"  # non-tradable reference series (NIFTY 50, India VIX, ...)


class InstrumentType(StrEnum):
    EQUITY = "equity"
    FUTURE = "future"
    OPTION = "option"
    INDEX = "index"


class OptionType(StrEnum):
    CALL = "CE"
    PUT = "PE"


class Horizon(StrEnum):
    INTRADAY = "intraday"
    SWING = "swing"  # days to weeks
    POSITIONAL = "positional"  # weeks to months


class Timeframe(StrEnum):
    M15 = "15m"
    H1 = "1h"
    D1 = "1d"
    W1 = "1w"
    MN1 = "1mo"


class Instrument(StrictModel):
    exchange: Exchange
    segment: Segment
    symbol: Annotated[str, Field(min_length=1, max_length=40, pattern=r"^[A-Z0-9&\-_.]+$")]
    instrument_type: InstrumentType
    expiry: date | None = None
    strike: Price | None = None
    option_type: OptionType | None = None
    lot_size: Annotated[int, Field(ge=1)] = 1

    @model_validator(mode="after")
    def _check_derivative_fields(self) -> Instrument:
        is_option = self.instrument_type is InstrumentType.OPTION
        is_future = self.instrument_type is InstrumentType.FUTURE
        if is_option and (self.expiry is None or self.strike is None or self.option_type is None):
            raise ValueError("options require expiry, strike and option_type")
        if is_future and (self.expiry is None or self.strike is not None or self.option_type):
            raise ValueError("futures require expiry and no strike/option_type")
        if not (is_option or is_future) and (self.expiry or self.strike or self.option_type):
            raise ValueError("only derivatives may carry expiry/strike/option_type")
        if (is_option or is_future) and self.segment is not Segment.FNO:
            raise ValueError("derivatives must be in the fno segment")
        return self

    @property
    def key(self) -> str:
        parts = [self.exchange.value, self.symbol]
        if self.expiry:
            parts.append(self.expiry.isoformat())
        if self.strike is not None:
            parts.append(str(self.strike))
        if self.option_type:
            parts.append(self.option_type.value)
        return ":".join(parts)


class EvidenceKind(StrEnum):
    MESSAGE = "message"  # another bus message (by message_id)
    DOCUMENT = "document"  # filing, article, transcript (by document id)
    DATASET = "dataset"  # point-in-time dataset slice
    TOOL_CALL = "tool_call"  # logged deterministic tool invocation


class EvidenceRef(StrictModel):
    kind: EvidenceKind
    ref_id: Annotated[str, Field(min_length=1, max_length=200)]
    available_at: UtcDatetime
    uri: Annotated[str, Field(max_length=2000)] | None = None
    # Verbatim span from the source; required for document evidence so claims are traceable.
    excerpt: Annotated[str, Field(max_length=2000)] | None = None

    @model_validator(mode="after")
    def _documents_need_excerpt(self) -> EvidenceRef:
        if self.kind is EvidenceKind.DOCUMENT and not self.excerpt:
            raise ValueError("document evidence must quote the source excerpt")
        return self
