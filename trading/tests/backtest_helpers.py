"""Builders for backtest tests: hand-specified bars and seeded random markets."""

from __future__ import annotations

from collections.abc import Callable, Sequence
from datetime import date, timedelta
from decimal import Decimal

import numpy as np

from aitrader.agents.data.agent import BARS, CORPORATE_ACTIONS, MarketData, bar_entity
from aitrader.agents.data.calendar import daily_bar_window
from aitrader.backtest.config import load_backtest_config
from aitrader.backtest.engine import BacktestEngine, DecisionContext
from aitrader.backtest.models import OrderIntent
from aitrader.schemas.base import Instrument, Timeframe
from aitrader.schemas.market import Bar, CorporateAction
from aitrader.storage import PitRecord, PitStore
from tests.data_helpers import NIFTY, eod, weekdays

START = date(2025, 1, 6)


def bar_record(
    inst: Instrument, day: date, o: float, h: float, lo: float, c: float, v: int = 1_000_000
) -> PitRecord:
    start, end = daily_bar_window(day)
    bar = Bar(
        instrument=inst,
        timeframe=Timeframe.D1,
        bar_start=start,
        bar_end=end,
        open=Decimal(str(round(o, 4))),
        high=Decimal(str(round(h, 4))),
        low=Decimal(str(round(lo, 4))),
        close=Decimal(str(round(c, 4))),
        volume=v,
    )
    return PitRecord(
        dataset=BARS,
        entity=bar_entity(inst, Timeframe.D1),
        event_time=end,
        available_at=eod(day),
        payload=bar.model_dump(mode="json"),
    )


def put(
    store: PitStore, inst: Instrument, day: date, o: float, h: float, lo: float, c: float, v: int = 1_000_000
) -> None:
    store.write([bar_record(inst, day, o, h, lo, c, v)], ingested_at=eod(day))


def calendar(store: PitStore, n: int, start: date = START) -> list[date]:
    days = weekdays(start, n)
    rows = [bar_record(NIFTY, d, 25000 + i, 25100 + i, 24900 + i, 25000 + i) for i, d in enumerate(days)]
    store.write(rows, ingested_at=eod(days[-1]))
    return days


def flat(
    store: PitStore, inst: Instrument, days: Sequence[date], price: float = 100.0, v: int = 1_000_000
) -> None:
    """Gently oscillating prices so volatility (and so slippage) is well defined."""
    rows = []
    for i, d in enumerate(days):
        c = price * (1 + 0.002 * ((-1) ** i))
        rows.append(bar_record(inst, d, c, c * 1.005, c * 0.995, c, v))
    store.write(rows, ingested_at=eod(days[-1]))


def random_walk(
    store: PitStore,
    inst: Instrument,
    days: Sequence[date],
    seed: int,
    drift: float = 0.0005,
    keep: int | None = None,
) -> None:
    """Seeded random walk over ``days``; ``keep`` stores only the first N bars of that same path."""
    rng = np.random.default_rng(seed)
    closes = 100 * np.exp(np.cumsum(rng.normal(drift, 0.015, len(days))))
    prev = 100.0
    rows = []
    for i, (d, c) in enumerate(zip(days, closes, strict=True)):
        o = prev * (1 + rng.normal(0, 0.004))
        h = max(o, c) * (1 + abs(rng.normal(0, 0.006)))
        lo = min(o, c) * (1 - abs(rng.normal(0, 0.006)))
        volume = int(rng.integers(500_000, 2_000_000))
        prev = float(c)
        if keep is None or i < keep:
            rows.append(bar_record(inst, d, o, h, lo, float(c), volume))
    store.write(rows, ingested_at=eod(days[-1]))


def add_action(store: PitStore, action: CorporateAction) -> None:
    store.write(
        [
            PitRecord(
                dataset=CORPORATE_ACTIONS,
                entity=action.instrument.key,
                event_time=action.announced_at,
                available_at=action.announced_at,
                payload=action.model_dump(mode="json"),
            )
        ],
        ingested_at=action.announced_at,
    )


def engine(store: PitStore, **overrides: object) -> BacktestEngine:
    config = load_backtest_config()
    if overrides:
        config = config.model_validate({**config.model_dump(), **overrides})
    return BacktestEngine(MarketData.for_backtest(store), config)


class Scripted:
    """Emits pre-programmed intents on given decision days and records what it saw."""

    name = "scripted"

    def __init__(self, script: dict[date, Callable[[DecisionContext], list[OrderIntent]]]) -> None:
        self.script = script
        self.seen: list[tuple[date, date | None]] = []

    @property
    def params(self) -> dict[str, float | int | str | bool]:
        return {"steps": len(self.script)}

    def decide(self, ctx: DecisionContext) -> list[OrderIntent]:
        self.seen.append((ctx.day, None))
        fn = self.script.get(ctx.day)
        return fn(ctx) if fn else []


def announced(day: date) -> object:
    return eod(day) - timedelta(hours=2)
