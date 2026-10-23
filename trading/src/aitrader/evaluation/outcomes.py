"""Forward-looking outcome measurement (evaluation only)."""

from __future__ import annotations

from datetime import datetime, timedelta

from aitrader.agents.data.agent import MarketData
from aitrader.schemas.base import Instrument, Timeframe
from aitrader.schemas.market import Bar

FAR_FUTURE = timedelta(days=3650)


def bars_after(
    market: MarketData, instrument: Instrument, as_of: datetime, sessions: int, data_as_of: datetime
) -> list[Bar]:
    """The next ``sessions`` daily bars after ``as_of``, adjusted as of ``data_as_of`` (the end of
    the evaluation data), so the forward path is on the same price basis as its start."""
    series = market.bars(instrument, Timeframe.D1, data_as_of, start=as_of - timedelta(days=10))
    before = [b for b in series.bars if b.bar_end <= as_of]
    after = [b for b in series.bars if b.bar_end > as_of]
    return (before[-1:] + after[:sessions]) if before else []


def forward_return(
    market: MarketData, instrument: Instrument, as_of: datetime, sessions: int, data_as_of: datetime
) -> float | None:
    path = bars_after(market, instrument, as_of, sessions, data_as_of)
    if len(path) < sessions + 1:
        return None
    return float(path[-1].close) / float(path[0].close) - 1


def r_outcome(
    path: list[Bar], direction: int, entry: float, stop: float, target: float
) -> tuple[float, str] | None:
    """R multiple of a setup over a bar path (first bar = signal day, never traded): the stop
    is assumed hit first when a bar spans both levels; unresolved trades are marked at the
    last close."""
    risk = (entry - stop) * direction
    if risk <= 0 or len(path) < 2:
        return None
    for bar in path[1:]:
        lo, hi = float(bar.low), float(bar.high)
        hit_stop = lo <= stop if direction > 0 else hi >= stop
        hit_target = hi >= target if direction > 0 else lo <= target
        if hit_stop:
            return -1.0, "stop"
        if hit_target:
            return (target - entry) * direction / risk, "target"
    return (float(path[-1].close) - entry) * direction / risk, "open"
