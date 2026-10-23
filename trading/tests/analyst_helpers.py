"""Synthetic markets with planted structure for analyst tests."""

from __future__ import annotations

from collections.abc import Sequence
from datetime import date

import numpy as np

from aitrader.agents.data.agent import MarketData
from aitrader.schemas.base import Instrument
from aitrader.storage import PitStore
from tests.backtest_helpers import bar_record
from tests.data_helpers import NIFTY, eod, weekdays

START = date(2023, 1, 2)


def write_closes(
    store: PitStore,
    inst: Instrument,
    days: Sequence[date],
    closes: Sequence[float],
    *,
    oi: Sequence[float] | None = None,
    volume: Sequence[int] | None = None,
    spread: float = 0.01,
) -> None:
    rows = []
    prev = float(closes[0])
    for i, (d, c) in enumerate(zip(days, closes, strict=True)):
        o = prev
        h, lo = max(o, c) * (1 + spread / 2), min(o, c) * (1 - spread / 2)
        rec = bar_record(inst, d, o, h, lo, float(c), int(volume[i]) if volume is not None else 1_000_000)
        if oi is not None:
            rec.payload["open_interest"] = int(oi[i])
        rows.append(rec)
        prev = float(c)
    store.write(rows, ingested_at=eod(days[-1]))


def regime_index(
    store: PitStore, n: int, seed: int = 0, drifts: Sequence[tuple[int, float, float]] = ()
) -> list[date]:
    """NIFTY 50 with piecewise (length, drift, vol) regimes; returns the sessions."""
    days = weekdays(START, n)
    rng = np.random.default_rng(seed)
    rets: list[float] = []
    for length, drift, vol in drifts or ((n, 0.0004, 0.01),):
        rets.extend(rng.normal(drift, vol, length).tolist())
    closes = 20000 * np.exp(np.cumsum(rets[:n]))
    write_closes(store, NIFTY, days, closes.tolist())
    return days


def zigzag_trend(
    n: int, drift: float, amplitude: float = 0.04, period: int = 12, base: float = 100.0
) -> list[float]:
    i = np.arange(n)
    values: list[float] = (
        base * np.exp(drift * i) * (1 + amplitude * np.sin(2 * np.pi * i / period))
    ).tolist()
    return values


def market(store: PitStore) -> MarketData:
    return MarketData.for_backtest(store)
