"""Positioning and breadth tools: OI build-up classification, market breadth and bar
resampling. Deterministic and typed like every other tool."""

from __future__ import annotations

from collections.abc import Sequence
from datetime import date
from typing import Annotated, Literal

import numpy as np
from pydantic import Field

from aitrader.tools.registry import tool
from aitrader.tools.types import NonNegative, Positive, ToolOutput, arr, same_length

BuildUp = Literal["long_buildup", "short_buildup", "short_covering", "long_unwinding", "flat"]
_WEIGHT = {
    "long_buildup": 1.0,
    "short_covering": 0.5,
    "short_buildup": -1.0,
    "long_unwinding": -0.5,
    "flat": 0.0,
}


class OiBuildUp(ToolOutput):
    labels: tuple[BuildUp, ...]  # one per day after the first
    score: float  # -1 distribution .. +1 accumulation, weighted by relative OI change
    persistence_days: int  # consecutive most-recent days with the same sign
    oi_percentile: float  # current OI within the supplied history


def classify_buildup(d_price: float, d_oi: float) -> BuildUp:
    if d_price > 0 and d_oi > 0:
        return "long_buildup"
    if d_price < 0 and d_oi > 0:
        return "short_buildup"
    if d_price > 0 and d_oi < 0:
        return "short_covering"
    if d_price < 0 and d_oi < 0:
        return "long_unwinding"
    return "flat"


@tool(
    "derivs.oi_buildup",
    "1.0.0",
    example={"prices": [100, 101, 102, 101, 103], "open_interest": [1000, 1100, 1250, 1300, 1400]},
)
def oi_buildup(
    prices: Annotated[Sequence[Positive], Field(min_length=2)],
    open_interest: Annotated[Sequence[NonNegative], Field(min_length=2)],
) -> OiBuildUp:
    """Price/OI quadrants: rising price with rising OI is long build-up, falling price with
    rising OI is short build-up, and so on."""
    same_length(prices, open_interest)
    p, oi = arr(prices), arr(open_interest)
    labels = [
        classify_buildup(float(dp), float(doi)) for dp, doi in zip(np.diff(p), np.diff(oi), strict=True)
    ]
    weights = np.abs(np.diff(oi)) / np.where(oi[:-1] > 0, oi[:-1], 1.0)
    values = np.array([_WEIGHT[label] for label in labels])
    score = float((weights * values).sum() / weights.sum()) if weights.sum() > 0 else 0.0
    persistence = 0
    last_sign = 0.0
    for v in reversed(values):
        sign = float(np.sign(v))
        if sign == 0 or (last_sign and sign != last_sign):
            break
        last_sign = sign
        persistence += 1
    return OiBuildUp(
        labels=tuple(labels),
        score=score,
        persistence_days=persistence,
        oi_percentile=float((oi[:-1] < oi[-1]).mean()),
    )


class Breadth(ToolOutput):
    members: int
    pct_above_50dma: float | None
    pct_above_200dma: float | None
    advance_decline_ratio: float | None  # advancers / decliners on the last session
    new_highs: int  # at a 250-session closing high (where history allows)
    new_lows: int


@tool(
    "breadth.compute",
    "1.0.0",
    example={"closes": [[100, 101, 103], [50, 49, 48], [10, 10.5, 10.4]]},
)
def breadth(
    closes: Annotated[Sequence[Annotated[Sequence[Positive], Field(min_length=2)]], Field(min_length=1)],
) -> Breadth:
    """Breadth across a universe. Each inner list is one member's closes, oldest first."""
    above50, above200, adv, dec, highs, lows = [], [], 0, 0, 0, 0
    for series in closes:
        c = arr(series)
        if len(c) >= 50:
            above50.append(c[-1] > c[-50:].mean())
        if len(c) >= 200:
            above200.append(c[-1] > c[-200:].mean())
        adv += int(c[-1] > c[-2])
        dec += int(c[-1] < c[-2])
        window = c[-250:]
        if len(window) >= 20:
            highs += int(c[-1] >= np.max(window))
            lows += int(c[-1] <= np.min(window))
    return Breadth(
        members=len(closes),
        pct_above_50dma=float(np.mean(above50)) if above50 else None,
        pct_above_200dma=float(np.mean(above200)) if above200 else None,
        advance_decline_ratio=adv / dec if dec else None,
        new_highs=highs,
        new_lows=lows,
    )


class ResampledBars(ToolOutput):
    period_end: tuple[date, ...]
    open: tuple[float, ...]
    high: tuple[float, ...]
    low: tuple[float, ...]
    close: tuple[float, ...]
    volume: tuple[float, ...]
    complete: tuple[bool, ...]  # False for a period still in progress at the last date


@tool(
    "ta.resample",
    "1.0.0",
    example={
        "dates": ["2026-10-19", "2026-10-20", "2026-10-21", "2026-10-26"],
        "open": [100, 101, 102, 104],
        "high": [102, 103, 104, 106],
        "low": [99, 100, 101, 103],
        "close": [101, 102, 103, 105],
        "volume": [10, 20, 30, 40],
        "period": "week",
    },
)
def resample(
    dates: Annotated[Sequence[date], Field(min_length=1)],
    open: Annotated[Sequence[Positive], Field(min_length=1)],
    high: Annotated[Sequence[Positive], Field(min_length=1)],
    low: Annotated[Sequence[Positive], Field(min_length=1)],
    close: Annotated[Sequence[Positive], Field(min_length=1)],
    volume: Annotated[Sequence[NonNegative], Field(min_length=1)],
    period: Literal["week", "month"],
    last_session_of_period: bool = False,
) -> ResampledBars:
    """Aggregate daily bars into ISO weeks or calendar months. The final period is marked
    incomplete unless the caller knows its last session has traded, so a weekly or monthly
    reading never treats a partial period as finished."""
    same_length(dates, open, high, low, close, volume)
    if list(dates) != sorted(dates) or len(set(dates)) != len(dates):
        raise ValueError("dates must be strictly increasing")

    def key(d: date) -> tuple[int, int]:
        iso = d.isocalendar()
        return (iso.year, iso.week) if period == "week" else (d.year, d.month)

    groups: list[list[int]] = []
    for i, d in enumerate(dates):
        if groups and key(dates[groups[-1][0]]) == key(d):
            groups[-1].append(i)
        else:
            groups.append([i])
    return ResampledBars(
        period_end=tuple(dates[g[-1]] for g in groups),
        open=tuple(float(open[g[0]]) for g in groups),
        high=tuple(float(max(high[i] for i in g)) for g in groups),
        low=tuple(float(min(low[i] for i in g)) for g in groups),
        close=tuple(float(close[g[-1]]) for g in groups),
        volume=tuple(float(sum(volume[i] for i in g)) for g in groups),
        complete=tuple([True] * (len(groups) - 1) + [last_session_of_period]),
    )
