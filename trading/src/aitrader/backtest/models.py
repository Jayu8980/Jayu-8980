"""Backtest data structures. Results are immutable models so runs can be hashed and compared."""

from __future__ import annotations

from dataclasses import dataclass
from datetime import date
from enum import StrEnum
from typing import Annotated, Literal

from pydantic import Field, model_validator

from aitrader.schemas.base import Instrument, StrictModel

Side = Literal["BUY", "SELL"]


class Intent(StrEnum):
    OPEN = "open"  # open or add
    CLOSE = "close"  # reduce or exit


class OrderIntent(StrictModel):
    """What a strategy asks for at a decision point; filled (or not) at the next open."""

    instrument: Instrument
    side: Side
    quantity: Annotated[int, Field(gt=0)]
    intent: Intent
    stop: Annotated[float, Field(gt=0)] | None = None
    target: Annotated[float, Field(gt=0)] | None = None
    limit_price: Annotated[float, Field(gt=0)] | None = None
    tag: Annotated[str, Field(max_length=60)] = ""

    @model_validator(mode="after")
    def _levels(self) -> OrderIntent:
        if self.quantity % self.instrument.lot_size:
            raise ValueError("quantity must be a multiple of the lot size")
        if self.intent is Intent.OPEN and self.stop is None:
            raise ValueError("opening orders need a protective stop")
        return self


@dataclass
class Position:
    instrument: Instrument
    quantity: int  # signed: negative = short
    avg_price: float
    entry_date: date
    stop: float | None = None
    target: float | None = None
    initial_risk_per_unit: float | None = None
    entry_costs: float = 0.0  # unallocated entry costs, released pro rata on exit
    tag: str = ""
    last_close: float | None = None


class FillRecord(StrictModel):
    day: date
    instrument_key: str
    side: Side
    quantity: int
    price: float
    reference_price: float  # open (or stop/target level) before slippage
    slippage_bps: float
    costs: float
    reason: Literal["order", "stop", "target", "delisted", "expiry", "end"]
    approximate_costs: bool


class Rejection(StrictModel):
    day: date
    instrument_key: str
    reason: str


class TradeRecord(StrictModel):
    instrument_key: str
    direction: Literal["long", "short"]
    entry_date: date
    exit_date: date
    quantity: int
    entry_price: float
    exit_price: float
    gross_pnl: float
    costs: float
    net_pnl: float
    r_multiple: float | None
    exit_reason: str
    tag: str
    holding_sessions: int


class EquityPoint(StrictModel):
    day: date
    equity: float
    cash: float
    gross_exposure: float  # sum |position value| / equity
    net_exposure: float


class PerformanceSummary(StrictModel):
    start: date
    end: date
    sessions: int
    initial_equity: float
    final_equity: float
    total_return: float
    cagr: float
    annual_vol: float
    sharpe: float
    sortino: float
    max_drawdown: float
    calmar: float
    avg_gross_exposure: float
    annual_turnover: float
    trades: int
    win_rate: float | None
    avg_r_multiple: float | None
    profit_factor: float | None
    avg_holding_sessions: float | None
    total_costs: float
    total_dividends: float


class BacktestResult(StrictModel):
    strategy: str
    params: dict[str, float | int | str | bool]
    config_digest: str
    start: date
    end: date
    equity_curve: tuple[EquityPoint, ...]
    trades: tuple[TradeRecord, ...]
    fills: tuple[FillRecord, ...]
    rejections: tuple[Rejection, ...]
    open_positions: dict[str, int] = Field(default_factory=dict)
    approximate_costs_used: bool
    warnings: tuple[str, ...] = ()
    summary: PerformanceSummary
    run_digest: str  # sha256 of everything above: identical inputs give identical digests
