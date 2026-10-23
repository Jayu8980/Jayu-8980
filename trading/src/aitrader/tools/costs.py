"""Indian equity and F&O transaction cost model with dated rate schedules.

Rates are loaded from config/costs.yaml so a backtest on any date uses the rates in force on
that date (STT on F&O changed in 2023, 2024 and 2026). Brokerage is per executed order.
"""

from __future__ import annotations

from datetime import date
from functools import lru_cache
from pathlib import Path
from typing import Annotated, Literal

import yaml
from pydantic import BaseModel, ConfigDict, Field

from aitrader.tools.registry import tool
from aitrader.tools.types import NonNegative, Positive, ToolOutput

Segment = Literal["equity_delivery", "equity_intraday", "futures", "options", "options_exercise"]
Side = Literal["BUY", "SELL"]
COSTS_FILE = Path(__file__).resolve().parents[3] / "config" / "costs.yaml"


class _Frozen(BaseModel):
    model_config = ConfigDict(frozen=True, extra="forbid")


class Stt(_Frozen):
    equity_delivery_buy: float
    equity_delivery_sell: float
    equity_intraday_sell: float
    futures_sell: float
    options_sell_premium: float
    options_exercise: float


class ExchangeTxn(_Frozen):
    equity: float
    futures: float
    options_premium: float


class StampDuty(_Frozen):
    equity_delivery: float
    equity_intraday: float
    futures: float
    options: float


class PctCap(_Frozen):
    pct: float
    cap: float


class Brokerage(_Frozen):
    equity_delivery: PctCap
    equity_intraday: PctCap
    futures: PctCap
    options_flat: float


class CostSchedule(_Frozen):
    effective_from: date
    approximate: bool = False
    note: str = ""
    stt: Stt
    exchange_txn: ExchangeTxn
    sebi_fee: Annotated[float, Field(ge=0, lt=0.001)]
    stamp_duty_buy: StampDuty
    gst: Annotated[float, Field(ge=0, lt=1)]
    brokerage: Brokerage


@lru_cache(maxsize=4)
def load_schedules(path: Path = COSTS_FILE) -> tuple[CostSchedule, ...]:
    raw = yaml.safe_load(path.read_text(encoding="utf-8"))
    schedules = tuple(
        sorted((CostSchedule.model_validate(s) for s in raw["schedules"]), key=lambda s: s.effective_from)
    )
    dates = [s.effective_from for s in schedules]
    if len(set(dates)) != len(dates):
        raise ValueError("duplicate effective_from dates in cost schedules")
    return schedules


def schedule_for(trade_date: date, path: Path = COSTS_FILE) -> CostSchedule:
    applicable = [s for s in load_schedules(path) if s.effective_from <= trade_date]
    if not applicable:
        raise ValueError(f"no cost schedule covers {trade_date}; add one rather than guessing")
    return applicable[-1]


class CostBreakdown(ToolOutput):
    turnover: float
    brokerage: float
    stt: float
    exchange_txn: float
    sebi_fee: float
    stamp_duty: float
    gst: float
    total: float
    total_bps: float
    schedule_effective_from: date
    approximate_rates: bool


@tool(
    "costs.india",
    "1.0.0",
    example={
        "segment": "equity_delivery",
        "side": "BUY",
        "price": 1500.0,
        "quantity": 100,
        "trade_date": "2026-10-21",
    },
)
def india_costs(
    segment: Segment,
    side: Side,
    price: Positive,  # premium for options; settlement intrinsic value for options_exercise
    quantity: Annotated[int, Field(gt=0)],
    trade_date: date,
) -> CostBreakdown:
    s = schedule_for(trade_date)
    turnover = price * quantity
    buy = side == "BUY"
    b = s.brokerage
    if segment == "equity_delivery":
        brokerage = min(turnover * b.equity_delivery.pct, b.equity_delivery.cap)
        stt = turnover * (s.stt.equity_delivery_buy if buy else s.stt.equity_delivery_sell)
        txn = turnover * s.exchange_txn.equity
        stamp = turnover * s.stamp_duty_buy.equity_delivery if buy else 0.0
    elif segment == "equity_intraday":
        brokerage = min(turnover * b.equity_intraday.pct, b.equity_intraday.cap)
        stt = 0.0 if buy else turnover * s.stt.equity_intraday_sell
        txn = turnover * s.exchange_txn.equity
        stamp = turnover * s.stamp_duty_buy.equity_intraday if buy else 0.0
    elif segment == "futures":
        brokerage = min(turnover * b.futures.pct, b.futures.cap)
        stt = 0.0 if buy else turnover * s.stt.futures_sell
        txn = turnover * s.exchange_txn.futures
        stamp = turnover * s.stamp_duty_buy.futures if buy else 0.0
    elif segment == "options":
        brokerage = b.options_flat
        stt = 0.0 if buy else turnover * s.stt.options_sell_premium
        txn = turnover * s.exchange_txn.options_premium
        stamp = turnover * s.stamp_duty_buy.options if buy else 0.0
    else:  # options_exercise: STT on intrinsic value, paid by the long holder
        if not buy:
            raise ValueError("exercise STT is charged to the option buyer; use side BUY")
        brokerage, txn, stamp = 0.0, 0.0, 0.0
        stt = turnover * s.stt.options_exercise
    sebi = turnover * s.sebi_fee
    gst = (brokerage + txn + sebi) * s.gst
    total = brokerage + stt + txn + sebi + stamp + gst
    return CostBreakdown(
        turnover=turnover,
        brokerage=brokerage,
        stt=stt,
        exchange_txn=txn,
        sebi_fee=sebi,
        stamp_duty=stamp,
        gst=gst,
        total=total,
        total_bps=total / turnover * 1e4,
        schedule_effective_from=s.effective_from,
        approximate_rates=s.approximate,
    )


class RoundTripCost(ToolOutput):
    entry_costs: float
    exit_costs: float
    slippage: float
    total: float
    per_unit: float


@tool(
    "costs.round_trip",
    "1.0.0",
    example={
        "segment": "equity_delivery",
        "direction": "LONG",
        "entry_price": 1500.0,
        "exit_price": 1600.0,
        "quantity": 100,
        "entry_date": "2026-10-21",
        "exit_date": "2026-10-30",
        "slippage_bps_each_side": 5.0,
    },
)
def round_trip(
    segment: Literal["equity_delivery", "equity_intraday", "futures", "options"],
    direction: Literal["LONG", "SHORT"],
    entry_price: Positive,
    exit_price: Positive,
    quantity: Annotated[int, Field(gt=0)],
    entry_date: date,
    exit_date: date,
    slippage_bps_each_side: NonNegative = 0.0,
) -> RoundTripCost:
    """All-in cost of entering and exiting, used by the risk-to-reward gate."""
    if exit_date < entry_date:
        raise ValueError("exit_date precedes entry_date")
    if segment == "equity_delivery" and direction == "SHORT":
        raise ValueError("cash-segment shorts are intraday only")
    entry_side: Side = "BUY" if direction == "LONG" else "SELL"
    exit_side: Side = "SELL" if direction == "LONG" else "BUY"
    entry = india_costs(segment, entry_side, entry_price, quantity, entry_date).total
    exit_ = india_costs(segment, exit_side, exit_price, quantity, exit_date).total
    slip = (entry_price + exit_price) * quantity * slippage_bps_each_side / 1e4
    total = entry + exit_ + slip
    return RoundTripCost(
        entry_costs=entry, exit_costs=exit_, slippage=slip, total=total, per_unit=total / quantity
    )
