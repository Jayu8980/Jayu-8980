"""Microstructure tools: spread/depth/imbalance, square-root impact, Almgren-Chriss
schedules, VWAP/TWAP benchmarks, transaction cost analysis and liquidation horizons.

Sign convention: slippage and impact are positive when they cost the trader.
"""

from __future__ import annotations

import math
from collections.abc import Sequence
from typing import Annotated, Literal

import numpy as np
from pydantic import Field

from aitrader.tools.registry import tool
from aitrader.tools.types import NonNegative, Positive, ToolOutput, Unit, arr, same_length

Side = Literal["BUY", "SELL"]


class BookMetrics(ToolOutput):
    mid: float
    spread_bps: float
    bid_depth: float  # quantity on the bid within depth_bps of mid
    ask_depth: float
    imbalance: float  # (bid - ask) / (bid + ask) of the top-of-book levels considered


@tool(
    "micro.spread_depth",
    "1.0.0",
    example={
        "bid_prices": [100.0, 99.95, 99.9],
        "bid_quantities": [500, 800, 1200],
        "ask_prices": [100.05, 100.1, 100.15],
        "ask_quantities": [400, 900, 1000],
    },
)
def spread_depth(
    bid_prices: Annotated[Sequence[Positive], Field(min_length=1)],
    bid_quantities: Annotated[Sequence[NonNegative], Field(min_length=1)],
    ask_prices: Annotated[Sequence[Positive], Field(min_length=1)],
    ask_quantities: Annotated[Sequence[NonNegative], Field(min_length=1)],
    depth_bps: Annotated[float, Field(gt=0)] = 25.0,
) -> BookMetrics:
    same_length(bid_prices, bid_quantities)
    same_length(ask_prices, ask_quantities)
    bp, bq, ap, aq = arr(bid_prices), arr(bid_quantities), arr(ask_prices), arr(ask_quantities)
    best_bid, best_ask = float(np.max(bp)), float(np.min(ap))
    if best_ask <= best_bid:
        raise ValueError("crossed or locked book")
    mid = (best_bid + best_ask) / 2
    band = mid * depth_bps / 1e4
    bid_depth = float(bq[bp >= mid - band].sum())
    ask_depth = float(aq[ap <= mid + band].sum())
    total = bid_depth + ask_depth
    return BookMetrics(
        mid=mid,
        spread_bps=(best_ask - best_bid) / mid * 1e4,
        bid_depth=bid_depth,
        ask_depth=ask_depth,
        imbalance=(bid_depth - ask_depth) / total if total > 0 else 0.0,
    )


class ImpactEstimate(ToolOutput):
    participation_of_adv: float
    impact_bps: float  # permanent+temporary market impact
    half_spread_bps: float
    total_bps: float


@tool(
    "micro.impact_sqrt",
    "1.0.0",
    example={"quantity": 20000, "adv": 2000000, "daily_vol": 0.018, "spread_bps": 3.0},
)
def impact_sqrt(
    quantity: Positive,
    adv: Positive,
    daily_vol: Annotated[float, Field(gt=0, lt=1)],
    spread_bps: Annotated[float, Field(ge=0)] = 0.0,
    coefficient: Annotated[float, Field(gt=0, le=5)] = 1.0,
) -> ImpactEstimate:
    """Square-root law: impact = Y * sigma_daily * sqrt(Q / ADV). Y is calibrated by TCA."""
    participation = quantity / adv
    impact = coefficient * daily_vol * math.sqrt(participation) * 1e4
    half = spread_bps / 2
    return ImpactEstimate(
        participation_of_adv=participation, impact_bps=impact, half_spread_bps=half, total_bps=impact + half
    )


class ExecutionSchedule(ToolOutput):
    holdings: tuple[float, ...]  # remaining position at each interval boundary, starts at total
    trades: tuple[float, ...]  # quantity per interval
    expected_cost: float  # currency, impact only
    cost_variance: float
    kappa: float


@tool(
    "micro.almgren_chriss",
    "1.0.0",
    example={
        "total_quantity": 100000,
        "n_intervals": 10,
        "interval_days": 0.1,
        "price_vol_per_day": 2.0,
        "temporary_impact": 2.5e-6,
        "permanent_impact": 2.5e-7,
        "risk_aversion": 1e-6,
    },
)
def almgren_chriss(
    total_quantity: Positive,
    n_intervals: Annotated[int, Field(ge=1, le=500)],
    interval_days: Positive,
    price_vol_per_day: Positive,  # sigma in currency per share per sqrt(day)
    temporary_impact: Positive,  # eta: currency per share per (share/day)
    permanent_impact: NonNegative,  # gamma: currency per share per share
    risk_aversion: NonNegative,  # lambda; 0 gives the risk-neutral (TWAP) schedule
) -> ExecutionSchedule:
    """Optimal liquidation trajectory, Almgren & Chriss (2000), linear impact."""
    x_total, n, tau = total_quantity, n_intervals, interval_days
    eta_tilde = temporary_impact * (1 - permanent_impact * tau / (2 * temporary_impact))
    if eta_tilde <= 0:
        raise ValueError("permanent impact too large relative to temporary impact for this interval")
    t = np.arange(n + 1) * tau
    horizon = n * tau
    if risk_aversion == 0:
        kappa = 0.0
        holdings = x_total * (1 - t / horizon)
    else:
        kappa_tilde_sq = risk_aversion * price_vol_per_day**2 / eta_tilde
        kappa = math.acosh(0.5 * kappa_tilde_sq * tau * tau + 1) / tau
        if kappa * horizon > 700:
            raise ValueError("risk aversion too high: schedule degenerates to immediate execution")
        holdings = x_total * np.sinh(kappa * (horizon - t)) / math.sinh(kappa * horizon)
    trades = -np.diff(holdings)
    expected = 0.5 * permanent_impact * x_total**2 + (eta_tilde / tau) * float((trades**2).sum())
    variance = price_vol_per_day**2 * tau * float((holdings[1:] ** 2).sum())
    return ExecutionSchedule(
        holdings=tuple(float(v) for v in holdings),
        trades=tuple(float(v) for v in trades),
        expected_cost=expected,
        cost_variance=variance,
        kappa=kappa,
    )


class Benchmark(ToolOutput):
    value: float


@tool("micro.vwap", "1.0.0", example={"prices": [100, 101, 102], "volumes": [1000, 3000, 1000]})
def vwap(
    prices: Annotated[Sequence[Positive], Field(min_length=1)],
    volumes: Annotated[Sequence[NonNegative], Field(min_length=1)],
) -> Benchmark:
    same_length(prices, volumes)
    p, v = arr(prices), arr(volumes)
    if v.sum() == 0:
        raise ValueError("zero volume")
    return Benchmark(value=float((p * v).sum() / v.sum()))


@tool("micro.twap", "1.0.0", example={"prices": [100, 101, 102]})
def twap(prices: Annotated[Sequence[Positive], Field(min_length=1)]) -> Benchmark:
    return Benchmark(value=float(arr(prices).mean()))


class TcaReport(ToolOutput):
    filled_quantity: float
    avg_price: float
    slippage_vs_arrival_bps: float
    slippage_vs_vwap_bps: float | None
    estimation_error_bps: float | None  # realised (vs arrival) minus pre-trade estimate


@tool(
    "micro.tca",
    "1.0.0",
    example={
        "side": "BUY",
        "fill_prices": [100.1, 100.2],
        "fill_quantities": [500, 500],
        "arrival_price": 100.0,
        "vwap_benchmark": 100.12,
        "estimated_cost_bps": 8.0,
    },
)
def tca(
    side: Side,
    fill_prices: Annotated[Sequence[Positive], Field(min_length=1)],
    fill_quantities: Annotated[Sequence[Positive], Field(min_length=1)],
    arrival_price: Positive,
    vwap_benchmark: Positive | None = None,
    estimated_cost_bps: float | None = None,
) -> TcaReport:
    same_length(fill_prices, fill_quantities)
    p, q = arr(fill_prices), arr(fill_quantities)
    avg = float((p * q).sum() / q.sum())
    sign = 1.0 if side == "BUY" else -1.0
    vs_arrival = sign * (avg - arrival_price) / arrival_price * 1e4
    vs_vwap = sign * (avg - vwap_benchmark) / vwap_benchmark * 1e4 if vwap_benchmark else None
    return TcaReport(
        filled_quantity=float(q.sum()),
        avg_price=avg,
        slippage_vs_arrival_bps=vs_arrival,
        slippage_vs_vwap_bps=vs_vwap,
        estimation_error_bps=vs_arrival - estimated_cost_bps if estimated_cost_bps is not None else None,
    )


class LiquidationProfile(ToolOutput):
    fraction_of_adv: float
    days_to_liquidate: float
    exceeds_limit: bool


@tool(
    "micro.liquidation",
    "1.0.0",
    example={"position_quantity": 300000, "adv": 2000000, "max_participation": 0.1, "max_days": 1.0},
)
def liquidation(
    position_quantity: NonNegative,
    adv: Positive,
    max_participation: Annotated[Unit, Field(gt=0)],
    max_days: Positive,
) -> LiquidationProfile:
    days = position_quantity / (adv * max_participation)
    return LiquidationProfile(
        fraction_of_adv=position_quantity / adv, days_to_liquidate=days, exceeds_limit=days > max_days
    )


class SlicePlan(ToolOutput):
    slices: tuple[int, ...]  # child order quantities, each a whole number of lots
    interval_seconds: float  # spacing between child orders
    max_child_quantity: int


@tool(
    "micro.slicer",
    "1.0.0",
    example={
        "quantity": 3000,
        "lot_size": 75,
        "price": 120.0,
        "freeze_quantity": 1801,
        "max_order_value": 200000.0,
        "algo": "twap",
        "horizon_minutes": 60,
    },
)
def slicer(
    quantity: Annotated[int, Field(gt=0)],
    lot_size: Annotated[int, Field(ge=1)],
    price: Positive,
    max_order_value: Positive,
    algo: Literal["marketable_limit", "twap", "vwap", "pov", "iceberg"],
    horizon_minutes: Annotated[int, Field(ge=1)],
    freeze_quantity: Annotated[int, Field(ge=1)] | None = None,
) -> SlicePlan:
    """Split an order into child orders that respect the exchange freeze quantity (orders at
    or above it are refused, so children stay strictly below) and the per-order value cap.
    Scheduled algos also spread the order across the horizon in up to 12 roughly equal
    children, at least 15 minutes apart."""
    if quantity % lot_size:
        raise ValueError("quantity must be a whole number of lots")
    lots = quantity // lot_size
    max_lots = math.floor(max_order_value / (price * lot_size))
    if freeze_quantity is not None:
        max_lots = min(max_lots, (freeze_quantity - 1) // lot_size)
    if max_lots < 1:
        raise ValueError("a single lot exceeds the freeze quantity or the order value cap")
    n = math.ceil(lots / max_lots)
    if algo in ("twap", "vwap", "pov", "iceberg"):
        n = max(n, min(lots, 12, max(1, horizon_minutes // 15)))
    base, extra = divmod(lots, n)
    slices = tuple((base + (1 if i < extra else 0)) * lot_size for i in range(n))
    return SlicePlan(
        slices=slices,
        interval_seconds=0.0 if n == 1 else horizon_minutes * 60 / n,
        max_child_quantity=max_lots * lot_size,
    )
