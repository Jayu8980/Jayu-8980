"""Technical-analysis tools, computed numerically (never from chart images).

Lookahead guard: a swing pivot at bar i needs ``strength`` bars on both sides, so it only
becomes known at bar i + strength. Every pivot carries ``confirmed_at`` and structure/level
tools only use pivots confirmed by the last bar supplied.
"""

from __future__ import annotations

from collections.abc import Mapping, Sequence
from typing import Annotated, Literal

import numpy as np
from numpy.typing import NDArray
from pydantic import Field

from aitrader.tools.registry import tool
from aitrader.tools.types import (
    NonNegative,
    Positive,
    PositiveSeries,
    Score,
    ToolOutput,
    Unit,
    arr,
    same_length,
)

_RNG = np.random.default_rng(3)
_CLOSE = [float(v) for v in 100 * np.exp(np.cumsum(_RNG.normal(0.0005, 0.015, 260)))]
_HIGH = [c * (1 + abs(float(e))) for c, e in zip(_CLOSE, _RNG.normal(0, 0.006, 260), strict=True)]
_LOW = [c * (1 - abs(float(e))) for c, e in zip(_CLOSE, _RNG.normal(0, 0.006, 260), strict=True)]
_OPEN = [(h + lo) / 2 for h, lo in zip(_HIGH, _LOW, strict=True)]
_VOL = [float(v) for v in _RNG.integers(100_000, 500_000, 260)]
_BENCH = [float(v) for v in 100 * np.exp(np.cumsum(_RNG.normal(0.0003, 0.01, 260)))]
_HLC = {"high": _HIGH, "low": _LOW, "close": _CLOSE}

Bars = Annotated[Sequence[Positive], Field(min_length=3)]


def _hlc(
    high: Sequence[float], low: Sequence[float], close: Sequence[float]
) -> tuple[NDArray[np.float64], NDArray[np.float64], NDArray[np.float64]]:
    same_length(high, low, close)
    h, lo, c = arr(high), arr(low), arr(close)
    if np.any(h < lo) or np.any(c > h) or np.any(c < lo):
        raise ValueError("inconsistent high/low/close")
    return h, lo, c


def wilder_atr(
    h: NDArray[np.float64], lo: NDArray[np.float64], c: NDArray[np.float64], period: int
) -> NDArray[np.float64]:
    if len(c) <= period:
        raise ValueError("not enough bars for ATR")
    prev = np.concatenate([[c[0]], c[:-1]])
    tr = np.maximum(np.maximum(h - lo, np.abs(h - prev)), np.abs(lo - prev))
    tr[0] = h[0] - lo[0]
    out = np.empty(len(c) - period + 1)
    out[0] = tr[:period].mean()
    for i in range(1, len(out)):
        out[i] = (out[i - 1] * (period - 1) + tr[period + i - 1]) / period
    return out


class IndicatorSeries(ToolOutput):
    first_index: int  # index in the input of values[0]
    values: tuple[float, ...]
    last: float


@tool("ta.atr", "1.0.0", example={**_HLC, "period": 14})
def atr(
    high: Bars, low: Bars, close: Bars, period: Annotated[int, Field(ge=2, le=200)] = 14
) -> IndicatorSeries:
    """Wilder's average true range."""
    values = wilder_atr(*_hlc(high, low, close), period)
    return IndicatorSeries(first_index=period - 1, values=tuple(values.tolist()), last=float(values[-1]))


class Pivot(ToolOutput):
    index: int
    price: float
    kind: Literal["high", "low"]
    confirmed_at: int


def find_pivots(h: NDArray[np.float64], lo: NDArray[np.float64], strength: int) -> list[Pivot]:
    pivots: list[Pivot] = []
    for i in range(strength, len(h) - strength):
        left_h, right_h = h[i - strength : i], h[i + 1 : i + strength + 1]
        if h[i] > np.max(left_h) and h[i] >= np.max(right_h):
            pivots.append(Pivot(index=i, price=float(h[i]), kind="high", confirmed_at=i + strength))
        left_l, right_l = lo[i - strength : i], lo[i + 1 : i + strength + 1]
        if lo[i] < np.min(left_l) and lo[i] <= np.min(right_l):
            pivots.append(Pivot(index=i, price=float(lo[i]), kind="low", confirmed_at=i + strength))
    return pivots


class PivotList(ToolOutput):
    pivots: tuple[Pivot, ...]


@tool("ta.swing_points", "1.0.0", example={"high": _HIGH, "low": _LOW, "strength": 3})
def swing_points(high: Bars, low: Bars, strength: Annotated[int, Field(ge=1, le=20)] = 3) -> PivotList:
    same_length(high, low)
    return PivotList(pivots=tuple(find_pivots(arr(high), arr(low), strength)))


StructureLabel = Literal[
    "uptrend", "downtrend", "range", "breakout", "breakdown", "failed_breakout", "failed_breakdown"
]


class MarketStructure(ToolOutput):
    state: StructureLabel
    higher_highs: bool | None
    higher_lows: bool | None
    last_swing_high: float | None
    last_swing_low: float | None
    previous_swing_high: float | None
    previous_swing_low: float | None
    atr: float


@tool("ta.market_structure", "1.0.0", example={**_HLC, "strength": 3})
def market_structure(
    high: Bars,
    low: Bars,
    close: Bars,
    strength: Annotated[int, Field(ge=1, le=20)] = 3,
    atr_period: Annotated[int, Field(ge=2, le=100)] = 14,
    breakout_buffer_atr: NonNegative = 0.25,
    failure_lookback: Annotated[int, Field(ge=2, le=50)] = 5,
) -> MarketStructure:
    """HH/HL vs LH/LL structure plus breakout / failed-breakout detection on confirmed pivots."""
    h, lo, c = _hlc(high, low, close)
    last = len(c) - 1
    current_atr = float(wilder_atr(h, lo, c, atr_period)[-1])
    pivots = [p for p in find_pivots(h, lo, strength) if p.confirmed_at <= last]
    highs = [p.price for p in pivots if p.kind == "high"]
    lows = [p.price for p in pivots if p.kind == "low"]
    sh, sl = (highs[-1] if highs else None), (lows[-1] if lows else None)
    psh, psl = (highs[-2] if len(highs) > 1 else None), (lows[-2] if len(lows) > 1 else None)
    hh = sh > psh if sh is not None and psh is not None else None
    hl = sl > psl if sl is not None and psl is not None else None
    buffer = breakout_buffer_atr * current_atr
    window_start = max(0, last - failure_lookback)
    recent = c[window_start:last]
    # Failed breakouts are judged against the levels that existed before the attempt; the
    # failed attempt's own peak may since have become a confirmed pivot.
    ref_highs = [p.price for p in pivots if p.kind == "high" and p.confirmed_at <= window_start]
    ref_lows = [p.price for p in pivots if p.kind == "low" and p.confirmed_at <= window_start]
    ref_high = ref_highs[-1] if ref_highs else None
    ref_low = ref_lows[-1] if ref_lows else None
    state: StructureLabel
    if sh is not None and c[-1] > sh + buffer:
        state = "breakout"
    elif sl is not None and c[-1] < sl - buffer:
        state = "breakdown"
    elif ref_high is not None and len(recent) and np.max(recent) > ref_high + buffer and c[-1] < ref_high:
        state = "failed_breakout"
    elif ref_low is not None and len(recent) and np.min(recent) < ref_low - buffer and c[-1] > ref_low:
        state = "failed_breakdown"
    elif hh and hl:
        state = "uptrend"
    elif hh is False and hl is False:
        state = "downtrend"
    else:
        state = "range"
    return MarketStructure(
        state=state,
        higher_highs=hh,
        higher_lows=hl,
        last_swing_high=sh,
        last_swing_low=sl,
        previous_swing_high=psh,
        previous_swing_low=psl,
        atr=current_atr,
    )


class Level(ToolOutput):
    price: float
    kind: Literal["support", "resistance"]
    touches: int
    last_touch_index: int


class Levels(ToolOutput):
    levels: tuple[Level, ...]  # nearest to the last close first


@tool("ta.support_resistance", "1.0.0", example={**_HLC, "strength": 3})
def support_resistance(
    high: Bars,
    low: Bars,
    close: Bars,
    strength: Annotated[int, Field(ge=1, le=20)] = 3,
    atr_period: Annotated[int, Field(ge=2, le=100)] = 14,
    tolerance_atr: Positive = 0.5,
    max_levels: Annotated[int, Field(ge=1, le=20)] = 6,
) -> Levels:
    """Clusters confirmed pivots lying within ``tolerance_atr`` ATRs of each other."""
    h, lo, c = _hlc(high, low, close)
    last = len(c) - 1
    tol = tolerance_atr * float(wilder_atr(h, lo, c, atr_period)[-1])
    pivots = sorted(
        (p for p in find_pivots(h, lo, strength) if p.confirmed_at <= last), key=lambda p: p.price
    )
    clusters: list[list[Pivot]] = []
    for p in pivots:
        if clusters and p.price - np.mean([q.price for q in clusters[-1]]) <= tol:
            clusters[-1].append(p)
        else:
            clusters.append([p])
    close_now = float(c[-1])
    levels = [
        Level(
            price=float(np.mean([p.price for p in group])),
            kind="support" if np.mean([p.price for p in group]) <= close_now else "resistance",
            touches=len(group),
            last_touch_index=max(p.index for p in group),
        )
        for group in clusters
    ]
    levels.sort(key=lambda lv: (abs(lv.price - close_now), -lv.touches))
    return Levels(levels=tuple(levels[:max_levels]))


@tool("ta.anchored_vwap", "1.0.0", example={**_HLC, "volume": _VOL, "anchor_index": 200})
def anchored_vwap(
    high: Bars,
    low: Bars,
    close: Bars,
    volume: Annotated[Sequence[NonNegative], Field(min_length=3)],
    anchor_index: Annotated[int, Field(ge=0)],
) -> IndicatorSeries:
    """VWAP of typical price (H+L+C)/3 from the anchor bar onward."""
    h, lo, c = _hlc(high, low, close)
    same_length(close, volume)
    if anchor_index >= len(c):
        raise ValueError("anchor beyond the data")
    v = arr(volume)[anchor_index:]
    tp = ((h + lo + c) / 3)[anchor_index:]
    cum_v = np.cumsum(v)
    if cum_v[-1] == 0:
        raise ValueError("no volume after anchor")
    values = np.cumsum(tp * v) / np.where(cum_v == 0, np.nan, cum_v)
    values = np.where(np.isnan(values), tp, values)
    return IndicatorSeries(first_index=anchor_index, values=tuple(values.tolist()), last=float(values[-1]))


class RelativeStrength(ToolOutput):
    excess_returns: dict[int, float]  # lookback -> asset return minus benchmark return
    rs_line_slope_20: float | None  # slope of log(asset/benchmark) over the last 20 bars, per bar


@tool("ta.relative_strength", "1.0.0", example={"asset_close": _CLOSE, "benchmark_close": _BENCH})
def relative_strength(
    asset_close: PositiveSeries,
    benchmark_close: PositiveSeries,
    lookbacks: tuple[int, ...] = (20, 50, 200),
) -> RelativeStrength:
    same_length(asset_close, benchmark_close)
    a, b = arr(asset_close), arr(benchmark_close)
    out = {}
    for lb in lookbacks:
        if lb < 1:
            raise ValueError("lookbacks must be positive")
        if len(a) > lb:
            out[lb] = float(a[-1] / a[-1 - lb] - b[-1] / b[-1 - lb])
    slope = None
    if len(a) >= 20:
        line = np.log(a[-20:] / b[-20:])
        slope = float(np.polyfit(np.arange(20), line, 1)[0])
    return RelativeStrength(excess_returns=out, rs_line_slope_20=slope)


class VolatilityState(ToolOutput):
    bollinger_width: float  # (upper - lower) / middle
    width_percentile: float  # of current width within the history supplied
    historical_vol_annual: float
    regime: Literal["compressed", "normal", "expanded", "extreme"]


@tool("ta.volatility_state", "1.0.0", example={"close": _CLOSE})
def volatility_state(
    close: Annotated[Sequence[Positive], Field(min_length=40)],
    period: Annotated[int, Field(ge=5, le=100)] = 20,
    k: Positive = 2.0,
    trading_days: Annotated[int, Field(ge=200, le=366)] = 252,
) -> VolatilityState:
    c = arr(close)
    if len(c) < 2 * period:
        raise ValueError("need at least two periods of data")
    windows = np.lib.stride_tricks.sliding_window_view(c, period)
    mid, sd = windows.mean(axis=1), windows.std(axis=1, ddof=0)
    width = 2 * k * sd / mid
    pct = float((width[:-1] < width[-1]).mean())
    log_ret = np.diff(np.log(c[-(period + 1) :]))
    hv = float(log_ret.std(ddof=1) * np.sqrt(trading_days))
    regime: Literal["compressed", "normal", "expanded", "extreme"]
    regime = "compressed" if pct < 0.2 else "normal" if pct < 0.8 else "expanded" if pct < 0.97 else "extreme"
    return VolatilityState(
        bollinger_width=float(width[-1]), width_percentile=pct, historical_vol_annual=hv, regime=regime
    )


class VolumeProfile(ToolOutput):
    point_of_control: float
    value_area_high: float
    value_area_low: float
    bin_edges: tuple[float, ...]
    bin_volumes: tuple[float, ...]


@tool("ta.volume_profile", "1.0.0", example={"close": _CLOSE, "volume": _VOL, "bins": 24})
def volume_profile(
    close: Bars,
    volume: Annotated[Sequence[NonNegative], Field(min_length=3)],
    bins: Annotated[int, Field(ge=5, le=200)] = 24,
    value_area: Annotated[float, Field(gt=0.5, lt=1.0)] = 0.7,
) -> VolumeProfile:
    """Volume by price using bar closes (daily-bar approximation; tick data refines it)."""
    same_length(close, volume)
    c, v = arr(close), arr(volume)
    if v.sum() == 0:
        raise ValueError("zero volume")
    hist, edges = np.histogram(c, bins=bins, weights=v)
    poc = int(np.argmax(hist))
    lo_i = hi_i = poc
    covered = hist[poc]
    while covered < value_area * hist.sum():
        below = hist[lo_i - 1] if lo_i > 0 else -1.0
        above = hist[hi_i + 1] if hi_i < len(hist) - 1 else -1.0
        if above >= below:
            hi_i += 1
            covered += above
        else:
            lo_i -= 1
            covered += below
    centres = (edges[:-1] + edges[1:]) / 2
    return VolumeProfile(
        point_of_control=float(centres[poc]),
        value_area_high=float(edges[hi_i + 1]),
        value_area_low=float(edges[lo_i]),
        bin_edges=tuple(edges.tolist()),
        bin_volumes=tuple(hist.tolist()),
    )


class Gap(ToolOutput):
    index: int
    direction: Literal["up", "down"]
    gap_low: float
    gap_high: float


class Gaps(ToolOutput):
    unfilled: tuple[Gap, ...]


@tool("ta.gaps", "1.0.0", example={"open": _OPEN, **_HLC, "min_gap_fraction": 0.005})
def gaps(
    open: Bars,
    high: Bars,
    low: Bars,
    close: Bars,
    min_gap_fraction: Annotated[float, Field(gt=0, lt=0.5)] = 0.005,
) -> Gaps:
    """Price gaps (today's range entirely beyond yesterday's) not yet filled by later bars."""
    h, lo, _ = _hlc(high, low, close)
    same_length(open, close)
    found: list[Gap] = []
    for i in range(1, len(h)):
        if lo[i] > h[i - 1] * (1 + min_gap_fraction):
            g = Gap(index=i, direction="up", gap_low=float(h[i - 1]), gap_high=float(lo[i]))
            if i + 1 >= len(h) or np.min(lo[i + 1 :]) > g.gap_low:
                found.append(g)
        elif h[i] < lo[i - 1] * (1 - min_gap_fraction):
            g = Gap(index=i, direction="down", gap_low=float(h[i]), gap_high=float(lo[i - 1]))
            if i + 1 >= len(h) or np.max(h[i + 1 :]) < g.gap_high:
                found.append(g)
    return Gaps(unfilled=tuple(found))


DIRECTIONAL = ("1mo", "1w", "1d")
TIMING = ("1h", "15m")


class Alignment(ToolOutput):
    alignment_score: float  # +1 all directional timeframes agree up, -1 all down
    direction: Literal["up", "down", "mixed", "flat"]
    conflicts: tuple[str, ...]
    timing_score: float | None  # intraday timeframes, used for entry timing only


@tool(
    "ta.timeframe_alignment",
    "1.0.0",
    example={"scores": {"1mo": 0.6, "1w": 0.4, "1d": -0.2, "15m": 0.3}},
)
def timeframe_alignment(
    scores: Annotated[Mapping[Literal["1mo", "1w", "1d", "1h", "15m"], Score], Field(min_length=1)],
    weights: Mapping[Literal["1mo", "1w", "1d"], Positive] | None = None,
    conflict_threshold: Unit = 0.2,
) -> Alignment:
    """Agreement of the directional timeframes. Conflicts are listed, never averaged away;
    intraday timeframes never contribute to direction."""
    w: dict[str, float] = {"1mo": 1.0, "1w": 1.5, "1d": 1.0}
    w.update({str(k): v for k, v in (weights or {}).items()})
    directional: dict[str, float] = {tf: s for tf, s in scores.items() if tf in DIRECTIONAL}
    if not directional:
        raise ValueError("at least one directional timeframe (1mo, 1w, 1d) is required")
    num = sum(w[tf] * s for tf, s in directional.items())
    den = sum(w[tf] * abs(s) for tf, s in directional.items())
    score = num / den if den > 0 else 0.0
    conflicts = tuple(
        f"{a} {directional[a]:+.2f} vs {b} {directional[b]:+.2f}"
        for i, a in enumerate(DIRECTIONAL)
        for b in DIRECTIONAL[i + 1 :]
        if a in directional
        and b in directional
        and directional[a] * directional[b] < 0
        and min(abs(directional[a]), abs(directional[b])) >= conflict_threshold
    )
    direction: Literal["up", "down", "mixed", "flat"]
    if den == 0:
        direction = "flat"
    elif conflicts:
        direction = "mixed"
    else:
        direction = "up" if score > 0 else "down"
    timing = [s for tf, s in scores.items() if tf in TIMING]
    return Alignment(
        alignment_score=float(score),
        direction=direction,
        conflicts=conflicts,
        timing_score=float(np.mean(timing)) if timing else None,
    )
