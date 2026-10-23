"""Derivatives tools: Black-Scholes and binomial pricing, Greeks, implied volatility, smile
and term-structure metrics, IV percentile, PCR, max pain, OI levels, dealer gamma exposure,
strategy payoffs and barrier-hit probabilities.

Conventions: time in years, rates and vols annualised and continuously compounded,
option_type "CE" (call) or "PE" (put). Vega is per 1 vol point, theta per calendar day,
rho per 1% rate move.
"""

from __future__ import annotations

import math
from collections.abc import Sequence
from typing import Annotated, Literal

import numpy as np
from pydantic import BaseModel, ConfigDict, Field, model_validator
from scipy.optimize import brentq
from scipy.stats import norm

from aitrader.tools.registry import tool
from aitrader.tools.types import NonNegative, Positive, ToolOutput, arr, same_length

OptionKind = Literal["CE", "PE"]
Years = Annotated[float, Field(gt=0, le=10)]
Vol = Annotated[float, Field(gt=0, le=5)]
Rate = Annotated[float, Field(ge=-0.05, le=0.5)]

_EX = {
    "spot": 25000.0,
    "strike": 25200.0,
    "t_years": 30 / 365,
    "rate": 0.065,
    "vol": 0.14,
    "option_type": "CE",
}


class OptionValue(ToolOutput):
    price: float
    delta: float
    gamma: float
    vega: float
    theta: float
    rho: float
    d1: float
    d2: float


def _d1_d2(s: float, k: float, t: float, r: float, q: float, v: float) -> tuple[float, float]:
    d1 = (math.log(s / k) + (r - q + 0.5 * v * v) * t) / (v * math.sqrt(t))
    return d1, d1 - v * math.sqrt(t)


def bs_value(s: float, k: float, t: float, r: float, v: float, kind: str, q: float = 0.0) -> OptionValue:
    d1, d2 = _d1_d2(s, k, t, r, q, v)
    disc_r, disc_q = math.exp(-r * t), math.exp(-q * t)
    pdf = norm.pdf(d1)
    gamma = disc_q * pdf / (s * v * math.sqrt(t))
    vega = s * disc_q * pdf * math.sqrt(t) / 100
    if kind == "CE":
        price = s * disc_q * norm.cdf(d1) - k * disc_r * norm.cdf(d2)
        delta = disc_q * norm.cdf(d1)
        theta = (
            -s * disc_q * pdf * v / (2 * math.sqrt(t))
            - r * k * disc_r * norm.cdf(d2)
            + q * s * disc_q * norm.cdf(d1)
        )
        rho = k * t * disc_r * norm.cdf(d2) / 100
    else:
        price = k * disc_r * norm.cdf(-d2) - s * disc_q * norm.cdf(-d1)
        delta = -disc_q * norm.cdf(-d1)
        theta = (
            -s * disc_q * pdf * v / (2 * math.sqrt(t))
            + r * k * disc_r * norm.cdf(-d2)
            - q * s * disc_q * norm.cdf(-d1)
        )
        rho = -k * t * disc_r * norm.cdf(-d2) / 100
    return OptionValue(
        price=float(price),
        delta=float(delta),
        gamma=float(gamma),
        vega=float(vega),
        theta=float(theta / 365),
        rho=float(rho),
        d1=float(d1),
        d2=float(d2),
    )


@tool("derivs.black_scholes", "1.0.0", example=_EX)
def black_scholes(
    spot: Positive,
    strike: Positive,
    t_years: Years,
    rate: Rate,
    vol: Vol,
    option_type: OptionKind,
    dividend_yield: Annotated[float, Field(ge=0, le=0.2)] = 0.0,
) -> OptionValue:
    return bs_value(spot, strike, t_years, rate, vol, option_type, dividend_yield)


class ImpliedVol(ToolOutput):
    implied_vol: float
    iterations_bracket: tuple[float, float]


@tool("derivs.implied_vol", "1.0.0", example={**{k: v for k, v in _EX.items() if k != "vol"}, "price": 180.0})
def implied_vol(
    price: Positive,
    spot: Positive,
    strike: Positive,
    t_years: Years,
    rate: Rate,
    option_type: OptionKind,
    dividend_yield: Annotated[float, Field(ge=0, le=0.2)] = 0.0,
) -> ImpliedVol:
    """Brent root-find on Black-Scholes; rejects prices outside no-arbitrage bounds."""
    disc_r, disc_q = math.exp(-rate * t_years), math.exp(-dividend_yield * t_years)
    if option_type == "CE":
        lower, upper = max(spot * disc_q - strike * disc_r, 0.0), spot * disc_q
    else:
        lower, upper = max(strike * disc_r - spot * disc_q, 0.0), strike * disc_r
    if not lower < price < upper:
        raise ValueError(f"price {price} outside no-arbitrage bounds ({lower:.4f}, {upper:.4f})")
    lo, hi = 1e-4, 5.0

    def objective(v: float) -> float:
        return bs_value(spot, strike, t_years, rate, v, option_type, dividend_yield).price - price

    if objective(lo) > 0 or objective(hi) < 0:
        raise ValueError("implied vol outside [0.01%, 500%]")
    root = float(brentq(objective, lo, hi, xtol=1e-10, maxiter=200))
    return ImpliedVol(implied_vol=root, iterations_bracket=(lo, hi))


class BinomialValue(ToolOutput):
    price: float
    delta: float
    early_exercise_premium: float  # american - european (0 for european)


@tool("derivs.binomial", "1.0.0", example={**_EX, "option_type": "PE", "american": True, "steps": 200})
def binomial(
    spot: Positive,
    strike: Positive,
    t_years: Years,
    rate: Rate,
    vol: Vol,
    option_type: OptionKind,
    american: bool = True,
    steps: Annotated[int, Field(ge=10, le=5000)] = 500,
    dividend_yield: Annotated[float, Field(ge=0, le=0.2)] = 0.0,
) -> BinomialValue:
    """Cox-Ross-Rubinstein tree."""

    def price_tree(early: bool) -> tuple[float, float]:
        dt = t_years / steps
        u = math.exp(vol * math.sqrt(dt))
        d = 1 / u
        p = (math.exp((rate - dividend_yield) * dt) - d) / (u - d)
        if not 0 < p < 1:
            raise ValueError("tree is not arbitrage-free for these inputs; increase steps")
        disc = math.exp(-rate * dt)
        j = np.arange(steps + 1)
        prices = spot * u ** (steps - j) * d**j
        sign = 1.0 if option_type == "CE" else -1.0
        values = np.maximum(sign * (prices - strike), 0.0)
        level1 = values
        for step in range(steps - 1, -1, -1):
            prices = prices[:-1] / u
            values = disc * (p * values[:-1] + (1 - p) * values[1:])
            if early:
                values = np.maximum(values, sign * (prices - strike))
            if step == 1:
                level1 = values.copy()
        s_up, s_down = spot * u, spot * d
        delta = float((level1[0] - level1[1]) / (s_up - s_down))
        return float(values[0]), delta

    euro, _ = price_tree(False)
    value, delta = price_tree(american)
    return BinomialValue(price=value, delta=delta, early_exercise_premium=value - euro if american else 0.0)


class IvPercentile(ToolOutput):
    percentile: float  # share of history strictly below current IV
    rank: float  # (current - min) / (max - min)


@tool("derivs.iv_percentile", "1.0.0", example={"history": [0.12, 0.15, 0.11, 0.18, 0.14], "current": 0.16})
def iv_percentile(
    history: Annotated[Sequence[Positive], Field(min_length=5)], current: Positive
) -> IvPercentile:
    h = arr(history)
    span = float(np.max(h) - np.min(h))
    return IvPercentile(
        percentile=float((h < current).mean()),
        rank=float(np.clip((current - np.min(h)) / span, 0, 1)) if span > 0 else 0.5,
    )


class SmileMetrics(ToolOutput):
    atm_iv: float
    put_wing_iv: float
    call_wing_iv: float
    skew: float  # put wing IV - call wing IV
    wing_moneyness: float


@tool(
    "derivs.smile_metrics",
    "1.0.0",
    example={
        "strikes": [23500, 24000, 24500, 25000, 25500, 26000, 26500],
        "ivs": [0.19, 0.17, 0.155, 0.14, 0.135, 0.133, 0.135],
        "spot": 25000,
    },
)
def smile_metrics(
    strikes: Annotated[Sequence[Positive], Field(min_length=3)],
    ivs: Annotated[Sequence[Positive], Field(min_length=3)],
    spot: Positive,
    wing_moneyness: Annotated[float, Field(gt=0, lt=0.5)] = 0.05,
) -> SmileMetrics:
    same_length(strikes, ivs)
    k, v = arr(strikes), arr(ivs)
    order = np.argsort(k)
    k, v = k[order], v[order]
    lo, hi = spot * (1 - wing_moneyness), spot * (1 + wing_moneyness)
    if lo < k[0] or hi > k[-1]:
        raise ValueError("strikes do not cover the requested wings")
    atm, put, call = (float(np.interp(x, k, v)) for x in (spot, lo, hi))
    return SmileMetrics(
        atm_iv=atm, put_wing_iv=put, call_wing_iv=call, skew=put - call, wing_moneyness=wing_moneyness
    )


class TermStructure(ToolOutput):
    slope_per_year: float  # d(ATM IV)/d(T)
    inverted: bool  # front IV above back IV (typical of stress/events)
    front_iv: float
    back_iv: float


@tool(
    "derivs.term_structure", "1.0.0", example={"t_years": [0.02, 0.1, 0.25], "atm_ivs": [0.16, 0.145, 0.14]}
)
def term_structure(
    t_years: Annotated[Sequence[Positive], Field(min_length=2)],
    atm_ivs: Annotated[Sequence[Positive], Field(min_length=2)],
) -> TermStructure:
    same_length(t_years, atm_ivs)
    t, v = arr(t_years), arr(atm_ivs)
    order = np.argsort(t)
    t, v = t[order], v[order]
    slope = float(np.polyfit(t, v, 1)[0])
    return TermStructure(
        slope_per_year=slope, inverted=bool(v[0] > v[-1]), front_iv=float(v[0]), back_iv=float(v[-1])
    )


class OptionChainLevels(ToolOutput):
    pcr_oi: float
    pcr_volume: float | None
    max_pain: float
    call_wall: float  # strike with the highest call OI (resistance proxy)
    put_wall: float  # strike with the highest put OI (support proxy)


@tool(
    "derivs.chain_levels",
    "1.0.0",
    example={
        "strikes": [24500, 25000, 25500],
        "call_oi": [1000, 5000, 9000],
        "put_oi": [8000, 6000, 1500],
    },
)
def chain_levels(
    strikes: Annotated[Sequence[Positive], Field(min_length=2)],
    call_oi: Annotated[Sequence[NonNegative], Field(min_length=2)],
    put_oi: Annotated[Sequence[NonNegative], Field(min_length=2)],
    call_volume: Sequence[NonNegative] | None = None,
    put_volume: Sequence[NonNegative] | None = None,
) -> OptionChainLevels:
    """PCR, max pain (strike minimising total intrinsic payout to holders) and OI walls."""
    same_length(strikes, call_oi, put_oi)
    k, c, p = arr(strikes), arr(call_oi), arr(put_oi)
    if c.sum() == 0:
        raise ValueError("call OI is zero; PCR undefined")
    payout = [float((np.maximum(s - k, 0) * c).sum() + (np.maximum(k - s, 0) * p).sum()) for s in k]
    pcr_volume = None
    if call_volume is not None and put_volume is not None:
        same_length(strikes, call_volume, put_volume)
        total_calls = float(sum(call_volume))
        pcr_volume = float(sum(put_volume)) / total_calls if total_calls > 0 else None
    return OptionChainLevels(
        pcr_oi=float(p.sum() / c.sum()),
        pcr_volume=pcr_volume,
        max_pain=float(k[int(np.argmin(payout))]),
        call_wall=float(k[int(np.argmax(c))]),
        put_wall=float(k[int(np.argmax(p))]),
    )


class GammaExposure(ToolOutput):
    total_gex_per_1pct: float  # currency change in dealer delta per 1% spot move
    by_strike: tuple[float, ...]
    zero_gamma_level: float | None  # spot where modelled dealer GEX changes sign
    assumption: str


@tool(
    "derivs.dealer_gamma_exposure",
    "1.0.0",
    example={
        "strikes": [24500, 25000, 25500],
        "call_oi": [1000, 5000, 9000],
        "put_oi": [8000, 6000, 1500],
        "ivs": [0.16, 0.14, 0.13],
        "spot": 25000,
        "t_years": 7 / 365,
        "rate": 0.065,
        "lot_size": 75,
    },
)
def dealer_gamma_exposure(
    strikes: Annotated[Sequence[Positive], Field(min_length=2)],
    call_oi: Annotated[Sequence[NonNegative], Field(min_length=2)],
    put_oi: Annotated[Sequence[NonNegative], Field(min_length=2)],
    ivs: Annotated[Sequence[Positive], Field(min_length=2)],
    spot: Positive,
    t_years: Years,
    rate: Rate,
    lot_size: Annotated[int, Field(ge=1)],
) -> GammaExposure:
    """Assumes dealers are long calls and short puts (customers overwrite calls, buy puts).
    OI is in contracts. This is a positioning model, not observed dealer inventory."""
    same_length(strikes, call_oi, put_oi, ivs)
    k, c, p, v = arr(strikes), arr(call_oi), arr(put_oi), arr(ivs)

    def gex_at(s: float) -> np.ndarray:
        gammas = np.array(
            [bs_value(s, kk, t_years, rate, vv, "CE").gamma for kk, vv in zip(k, v, strict=True)]
        )
        return gammas * (c - p) * lot_size * s * s * 0.01

    by_strike = gex_at(spot)
    grid = np.linspace(spot * 0.85, spot * 1.15, 121)
    totals = np.array([gex_at(float(s)).sum() for s in grid])
    flips = np.where(np.diff(np.sign(totals)) != 0)[0]
    zero = None
    if len(flips):
        i = int(flips[np.argmin(np.abs(grid[flips] - spot))])
        x0, x1, y0, y1 = grid[i], grid[i + 1], totals[i], totals[i + 1]
        zero = float(x0 - y0 * (x1 - x0) / (y1 - y0))
    return GammaExposure(
        total_gex_per_1pct=float(by_strike.sum()),
        by_strike=tuple(float(x) for x in by_strike),
        zero_gamma_level=zero,
        assumption="dealers long calls, short puts",
    )


class Leg(BaseModel):
    model_config = ConfigDict(frozen=True, extra="forbid")

    kind: Literal["CE", "PE", "FUT", "STOCK"]
    strike: Positive | None = None  # options only
    entry_price: NonNegative  # premium for options, price for futures/stock
    quantity: int  # signed units (contracts x lot size); negative = short

    @model_validator(mode="after")
    def _non_zero(self) -> Leg:
        if self.quantity == 0:
            raise ValueError("leg quantity must be non-zero")
        return self


class PayoffProfile(ToolOutput):
    spot_grid: tuple[float, ...]
    pnl: tuple[float, ...]
    max_profit: float
    max_loss: float
    unbounded_profit: bool
    unbounded_loss: bool
    breakevens: tuple[float, ...]


@tool(
    "derivs.payoff",
    "1.0.0",
    example={
        "legs": [
            {"kind": "CE", "strike": 25000, "entry_price": 200, "quantity": 75},
            {"kind": "CE", "strike": 25500, "entry_price": 60, "quantity": -75},
        ],
        "spot_low": 23000,
        "spot_high": 27000,
    },
)
def payoff(
    legs: Annotated[Sequence[Leg], Field(min_length=1, max_length=8)],
    spot_low: Positive,
    spot_high: Positive,
    points: Annotated[int, Field(ge=11, le=2001)] = 401,
) -> PayoffProfile:
    """Expiry P&L of a multi-leg position over a spot grid."""
    if spot_high <= spot_low:
        raise ValueError("spot_high must exceed spot_low")
    for leg in legs:
        if (leg.kind in ("CE", "PE")) != (leg.strike is not None):
            raise ValueError("options need a strike; futures/stock must not have one")
    grid = np.linspace(spot_low, spot_high, points)

    def pnl_at(s: np.ndarray) -> np.ndarray:
        total = np.zeros_like(s)
        for leg in legs:
            if leg.kind == "CE":
                value = np.maximum(s - (leg.strike or 0.0), 0.0)
            elif leg.kind == "PE":
                value = np.maximum((leg.strike or 0.0) - s, 0.0)
            else:
                value = s
            total += (value - leg.entry_price) * leg.quantity
        return total

    pnl = pnl_at(grid)
    # Above the grid only calls, futures and stock keep moving; below it spot is floored at 0,
    # so downside P&L is bounded and max_loss covers it when spot_low reaches the risk region.
    up_slope = sum(leg.quantity for leg in legs if leg.kind in ("CE", "FUT", "STOCK"))
    breakevens: list[float] = []
    for i in range(len(grid)):
        if pnl[i] == 0:
            point = float(grid[i])  # exactly on the grid: count it once
        elif i + 1 < len(grid) and pnl[i] * pnl[i + 1] < 0:
            point = float(grid[i] - pnl[i] * (grid[i + 1] - grid[i]) / (pnl[i + 1] - pnl[i]))
        else:
            continue
        if not breakevens or point - breakevens[-1] > 1e-9 * max(1.0, abs(point)):
            breakevens.append(point)
    return PayoffProfile(
        spot_grid=tuple(float(x) for x in grid),
        pnl=tuple(float(x) for x in pnl),
        max_profit=float(np.max(pnl)),
        max_loss=float(np.min(pnl)),
        unbounded_profit=up_slope > 0,
        unbounded_loss=up_slope < 0,
        breakevens=tuple(breakevens),
    )


class BarrierProbability(ToolOutput):
    p_target_first: float
    p_stop_first: float
    model: str


@tool(
    "derivs.barrier_hit_probability",
    "1.0.0",
    example={"spot": 100, "target": 115, "stop": 95, "annual_drift": 0.0, "annual_vol": 0.3},
)
def barrier_hit_probability(
    spot: Positive,
    target: Positive,
    stop: Positive,
    annual_vol: Vol,
    annual_drift: Annotated[float, Field(ge=-2, le=2)] = 0.0,
) -> BarrierProbability:
    """Probability that price touches target before stop under GBM (no time limit).
    Works for longs (target > spot > stop) and shorts (stop > spot > target)."""
    if not (target > spot > stop or stop > spot > target):
        raise ValueError("spot must lie strictly between target and stop")
    nu = annual_drift - 0.5 * annual_vol**2  # drift of log price
    a = math.log(target / spot)
    b = math.log(stop / spot)
    if abs(nu) < 1e-12:
        p = (0 - b) / (a - b)
    else:
        k = -2 * nu / annual_vol**2

        def scale(x: float) -> float:
            return math.exp(k * x)

        p = (scale(0) - scale(b)) / (scale(a) - scale(b))
    p = min(max(p, 0.0), 1.0)
    return BarrierProbability(p_target_first=p, p_stop_first=1 - p, model="gbm_two_barrier")


class TerminalProbability(ToolOutput):
    probability: float
    model: str


@tool("derivs.prob_above", "1.0.0", example={**_EX, "option_type": "CE"})
def prob_above(
    spot: Positive,
    strike: Positive,
    t_years: Years,
    rate: Rate,
    vol: Vol,
    option_type: OptionKind = "CE",
) -> TerminalProbability:
    """Risk-neutral probability that spot finishes above (CE) / below (PE) the strike: N(+/-d2)."""
    _, d2 = _d1_d2(spot, strike, t_years, rate, 0.0, vol)
    p = float(norm.cdf(d2) if option_type == "CE" else norm.cdf(-d2))
    return TerminalProbability(probability=p, model="risk_neutral_terminal")
