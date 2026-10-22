"""Data-layer payloads published by the data agent."""

from __future__ import annotations

from datetime import date
from decimal import Decimal
from enum import StrEnum
from typing import Annotated, Literal

from pydantic import Field, model_validator

from aitrader.schemas.base import (
    Instrument,
    Price,
    ShortText,
    StrictModel,
    Timeframe,
    UtcDatetime,
)


class Bar(StrictModel):
    kind: Literal["bar"] = "bar"
    instrument: Instrument
    timeframe: Timeframe
    bar_start: UtcDatetime
    bar_end: UtcDatetime
    open: Price
    high: Price
    low: Price
    close: Price
    volume: Annotated[int, Field(ge=0)]
    open_interest: Annotated[int, Field(ge=0)] | None = None
    delivery_qty: Annotated[int, Field(ge=0)] | None = None
    # Cumulative back-adjustment factor applied for splits/bonuses/dividends known as of
    # the envelope's available_at. Raw prices are recoverable as adjusted / factor.
    adjustment_factor: Annotated[Decimal, Field(gt=0)] = Decimal(1)

    @model_validator(mode="after")
    def _check_ohlc(self) -> Bar:
        if self.bar_end <= self.bar_start:
            raise ValueError("bar_end must be after bar_start")
        if not (self.low <= min(self.open, self.close) and self.high >= max(self.open, self.close)):
            raise ValueError("OHLC values are inconsistent")
        return self


class CorporateActionType(StrEnum):
    SPLIT = "split"
    BONUS = "bonus"
    DIVIDEND = "dividend"
    RIGHTS = "rights"
    MERGER = "merger"
    DEMERGER = "demerger"
    SYMBOL_CHANGE = "symbol_change"
    DELISTING = "delisting"


class CorporateAction(StrictModel):
    kind: Literal["corporate_action"] = "corporate_action"
    instrument: Instrument
    action_type: CorporateActionType
    announced_at: UtcDatetime
    ex_date: date
    ratio_numerator: Annotated[Decimal, Field(gt=0)] | None = None
    ratio_denominator: Annotated[Decimal, Field(gt=0)] | None = None
    cash_amount: Annotated[Decimal, Field(ge=0)] | None = None
    new_symbol: str | None = None


class DataQualityIssue(StrEnum):
    GAP = "gap"
    STALE_FEED = "stale_feed"
    OUTLIER = "outlier"
    SCHEMA_VIOLATION = "schema_violation"
    LATE_DATA = "late_data"
    SOURCE_DISAGREEMENT = "source_disagreement"


class Severity(StrEnum):
    INFO = "info"
    WARNING = "warning"
    CRITICAL = "critical"


class DataQualityAlert(StrictModel):
    kind: Literal["data_quality_alert"] = "data_quality_alert"
    issue: DataQualityIssue
    severity: Severity
    source: Annotated[str, Field(min_length=1, max_length=100)]
    instrument: Instrument | None = None
    detail: ShortText
    detected_at: UtcDatetime
