"""Shared helpers for data-layer tests."""

from __future__ import annotations

from datetime import UTC, date, datetime, time, timedelta
from decimal import Decimal

from aitrader.agents.data.agent import BARS, DataAgent, bar_entity
from aitrader.agents.data.calendar import daily_bar_window, ist_datetime
from aitrader.audit import InMemoryAuditLog
from aitrader.bus import InMemoryBus
from aitrader.config import load_system_config
from aitrader.schemas.base import Exchange, Instrument, InstrumentType, Segment, Timeframe
from aitrader.schemas.envelope import Envelope, Topic
from aitrader.schemas.market import Bar
from aitrader.storage import PitRecord, PitStore

RELIANCE = Instrument(
    exchange=Exchange.NSE, segment=Segment.CASH, symbol="RELIANCE", instrument_type=InstrumentType.EQUITY
)
NIFTY = Instrument(
    exchange=Exchange.NSE, segment=Segment.INDEX, symbol="NIFTY_50", instrument_type=InstrumentType.INDEX
)


class SettableClock:
    def __init__(self, now: datetime) -> None:
        self.now = now

    def __call__(self) -> datetime:
        return self.now


def eod(day: date) -> datetime:
    return ist_datetime(day, time(19, 0))


def make_bar(instrument: Instrument, day: date, close: float, volume: int = 100_000) -> Bar:
    start, end = daily_bar_window(day)
    c = Decimal(str(close))
    return Bar(
        instrument=instrument,
        timeframe=Timeframe.D1,
        bar_start=start,
        bar_end=end,
        open=c,
        high=c * Decimal("1.01"),
        low=c * Decimal("0.99"),
        close=c,
        volume=volume,
    )


def store_bar(store: PitStore, bar: Bar, available_at: datetime | None = None) -> None:
    store.write(
        [
            PitRecord(
                dataset=BARS,
                entity=bar_entity(bar.instrument, bar.timeframe),
                event_time=bar.bar_end,
                available_at=available_at or eod(bar.bar_end.date()),
                payload=bar.model_dump(mode="json"),
            )
        ],
        ingested_at=datetime.now(UTC),
    )


def weekdays(start: date, n: int) -> list[date]:
    days: list[date] = []
    d = start
    while len(days) < n:
        if d.weekday() < 5:
            days.append(d)
        d += timedelta(days=1)
    return days


class Harness:
    def __init__(self, now: datetime) -> None:
        self.clock = SettableClock(now)
        self.audit = InMemoryAuditLog(self.clock)
        self.bus = InMemoryBus(self.audit)
        self.store = PitStore()
        self.agent = DataAgent(self.store, self.bus, load_system_config().data, self.clock)
        self.published: list[Envelope] = []

        async def capture(env: Envelope) -> None:
            self.published.append(env)

        self.bus.subscribe(list(Topic), capture, group="test")

    def topics(self, topic: Topic) -> list[Envelope]:
        return [e for e in self.published if e.topic is topic]
