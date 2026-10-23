"""The daily paper service: ingest EOD files from a drop folder, run due sessions, skip
holidays, wait for late files, checkpoint, resume after a restart, notify and publish the
dashboard."""

from __future__ import annotations

import asyncio
from datetime import date, time
from pathlib import Path

import pytest

from aitrader.agents.data.agent import DataAgent
from aitrader.agents.data.calendar import ist_datetime
from aitrader.audit import InMemoryAuditLog
from aitrader.bus import InMemoryBus
from aitrader.config import load_risk_limits, load_system_config
from aitrader.execution.orderbook import InMemoryOrderBook
from aitrader.feedback.attribution import AttributionAgent
from aitrader.notify import MemoryNotifier, NotificationAgent
from aitrader.paper.checkpoint import FileCheckpointStore
from aitrader.paper.feed import FolderFeed
from aitrader.paper.service import PaperService
from aitrader.state import InMemoryStateStore
from aitrader.storage import CachedPitReader, PitStore
from tests.data_helpers import SettableClock
from tests.nse_files import Ohlcv, cm_bhavcopy_text, index_close_text, synthetic_market, write_day
from tests.test_paper_trading import build

HISTORY = 260  # the trend agent needs 220 sessions
LIVE = 8
HOLIDAY = 3  # index into the live days: no files published that day


def preload(store: PitStore, days: list[date], nifty: list[Ohlcv], stocks: dict[str, list[Ohlcv]]) -> None:
    clock = SettableClock(ist_datetime(days[-1], time(23, 0)))
    bus = InMemoryBus(InMemoryAuditLog(clock))
    agent = DataAgent(store, bus, load_system_config().data, clock)
    for i, day in enumerate(days):
        asyncio.run(agent.ingest_eod("nse_index_close", index_close_text(day, nifty[i]), publish=False))
        day_stocks = {s: rows[i] for s, rows in stocks.items()}
        asyncio.run(agent.ingest_eod("nse_cm_bhavcopy", cm_bhavcopy_text(day, day_stocks), publish=False))


class World:
    def __init__(self, tmp: Path) -> None:
        # Seed 12: the index and UPA rise into the live window, so the run trades.
        self.days, self.nifty, self.stocks = synthetic_market(HISTORY + LIVE, seed=12)
        self.store = PitStore()
        preload(self.store, self.days[:HISTORY], self.nifty, self.stocks)
        self.live = self.days[HISTORY:]
        self.folder = tmp / "eod"
        self.checkpoints = FileCheckpointStore(tmp / "checkpoint.json")
        self.dashboard = tmp / "dashboard.html"
        self.wall = SettableClock(ist_datetime(self.live[0], time(18, 0)))
        self.notifier = MemoryNotifier()
        # Durable across restarts, as the Postgres state store and order book are.
        self.state = InMemoryStateStore()
        self.book = InMemoryOrderBook()

    def publish(self, i: int) -> None:
        if i == HOLIDAY:
            return
        k = HISTORY + i
        write_day(self.folder, self.live[i], self.nifty[k], {s: rows[k] for s, rows in self.stocks.items()})

    def service(self) -> PaperService:
        reader = CachedPitReader(self.store)
        trader = build(self.store, self.days, reader=reader, state=self.state, book=self.book)
        trader.attribution = AttributionAgent(trader.market, trader.cycle.rr.stats, trader.cycle.credibility)
        trader.attribution.attach(trader.bus)
        notifications = NotificationAgent(self.notifier)
        notifications.attach(trader.bus)
        return PaperService(
            trader=trader,
            data=DataAgent(self.store, trader.bus, load_system_config().data, self.wall),
            feed=FolderFeed(self.folder),
            reader=reader,
            checkpoints=self.checkpoints,
            signer=trader.execution.signer,
            limits=load_risk_limits(),
            criteria=load_system_config().rollout.paper_exit,
            clock=self.wall,
            start_date=self.live[0],
            dashboard_path=self.dashboard,
            notifications=notifications,
        )

    def at(self, i: int, hh: int) -> None:
        self.wall.now = ist_datetime(self.live[i], time(hh, 0))


@pytest.fixture(scope="module")
def world(tmp_path_factory: pytest.TempPathFactory) -> World:
    return World(tmp_path_factory.mktemp("paper"))


def test_service_runs_due_sessions_skips_holidays_and_waits_for_files(world: World) -> None:
    service = world.service()
    assert service.resume() is None
    world.publish(0)
    world.at(0, 18)
    assert asyncio.run(service.step()) == []  # files are not usable before 19:00 IST
    world.at(0, 21)
    assert [r.day for r in asyncio.run(service.step())] == [world.live[0]]
    for i in range(1, 6):
        world.publish(i)
    world.at(5, 21)
    done = asyncio.run(service.step())
    assert [r.day for r in done] == [world.live[i] for i in (1, 2, 4, 5)]
    assert any("holiday" in line for line in service.log)
    world.at(6, 21)  # day 6's files are late
    assert asyncio.run(service.step()) == []
    assert any("waiting" in line for line in service.log)
    checkpoint = world.checkpoints.load()
    assert checkpoint is not None and checkpoint.last_day == world.live[5]
    page = world.dashboard.read_text(encoding="utf-8")
    assert "Paper-to-live readiness" in page and "Sessions traded" in page
    assert len([m for m in world.notifier.messages if m.startswith("Paper trading")]) == 5
    assert all(r.reconciliation is not None and r.reconciliation.matched for r in service.trader.records)


def test_a_restarted_service_resumes_after_the_last_session(world: World) -> None:
    service = world.service()  # a new process: everything rebuilt, state from the checkpoint
    assert service.resume() == world.live[5]
    world.publish(6)
    world.publish(7)
    world.at(7, 21)
    done = asyncio.run(service.step())
    assert [r.day for r in done] == [world.live[6], world.live[7]]
    assert [r.day for r in service.trader.records] == [world.live[i] for i in (0, 1, 2, 4, 5, 6, 7)]
    report = service.readiness()
    assert not report.ready  # 7 sessions are far from the 60 required
    sessions = next(r for r in report.results if r.name == "Sessions traded")
    assert sessions.observed == "7" and not sessions.passed
    fills = service.trader.execution.book.fills()
    assert fills, "the live window should trade"
    traced = next(r for r in report.results if r.name.startswith("Fills traced"))
    assert traced.passed and traced.observed.startswith(f"{len(fills)} of {len(fills)}")
