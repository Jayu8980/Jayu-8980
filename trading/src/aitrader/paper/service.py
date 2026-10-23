"""The daily paper-trading service (Phase 7).

``step(now)`` does whatever is due at wall-clock time ``now`` and returns:
1. For each weekday after the last completed session whose EOD files should be out by now,
   it fetches and ingests them (idempotent: a re-ingested file writes nothing new).
2. A day whose index closes arrived is a session. The paper trader runs it on its logical IST
   timeline (open, session, reconcile, decision cycle). The service then saves a checkpoint,
   writes the dashboard and sends the daily summary.
3. A weekday with no files is a holiday once a later session has arrived. Until then the
   service waits and tries again on the next step, and the data agent's staleness check
   raises alerts if the feed stays silent.

Run it from a timer (``python -m aitrader.paper run-once``) or as a daemon; a restart resumes
from the last checkpoint, and the restored run is identical to an uninterrupted one.
"""

from __future__ import annotations

from collections.abc import Callable
from dataclasses import dataclass, field
from datetime import date, datetime, timedelta
from pathlib import Path

from aitrader.agents.data.agent import DataAgent, NotYetAvailableError
from aitrader.agents.data.calendar import ist_date, ist_datetime
from aitrader.agents.risk.tokens import TokenSigner
from aitrader.config.settings import PaperExitCriteria, RiskLimits
from aitrader.monitoring.dashboard import collect, render
from aitrader.notify.agent import NotificationAgent
from aitrader.paper.checkpoint import CheckpointStore
from aitrader.paper.feed import OPTIONAL_SOURCES, REQUIRED_SOURCES, EodFeed
from aitrader.paper.readiness import ReadinessReport, assess
from aitrader.paper.runner import DayRecord, PaperTrader
from aitrader.storage.pit import CachedPitReader


@dataclass
class PaperService:
    trader: PaperTrader
    data: DataAgent
    feed: EodFeed
    reader: CachedPitReader
    checkpoints: CheckpointStore
    signer: TokenSigner
    limits: RiskLimits
    criteria: PaperExitCriteria
    clock: Callable[[], datetime]
    start_date: date | None = None
    dashboard_path: Path | None = None
    notifications: NotificationAgent | None = None
    stage: str = "paper"
    log: list[str] = field(default_factory=list)

    def __post_init__(self) -> None:
        self._fetched: set[tuple[str, date]] = set()

    def resume(self) -> date | None:
        """Load the last checkpoint, if any. Returns the last completed session."""
        checkpoint = self.checkpoints.load()
        if checkpoint is not None:
            self.trader.restore(checkpoint)
        return self.trader.last_day

    def _first_day(self, now: datetime) -> date:
        if self.trader.last_day is not None:
            return self.trader.last_day + timedelta(days=1)
        return self.start_date or ist_date(now)

    def _due(self, now: datetime) -> list[date]:
        day, last = self._first_day(now), ist_date(now)
        out = []
        while day <= last:
            if day.weekday() < 5 and now >= self.data.available_at("nse_index_close", day):
                out.append(day)
            day += timedelta(days=1)
        return out

    async def _ingest(self, day: date) -> None:
        for source in (*REQUIRED_SOURCES, *OPTIONAL_SOURCES):
            if (source, day) in self._fetched or self.clock() < self.data.available_at(source, day):
                continue
            text = self.feed.fetch(source, day)
            if text is None:
                continue
            try:
                if source == "nse_participant_oi":
                    await self.data.ingest_participant_oi(text, day)
                else:
                    report = await self.data.ingest_eod(source, text)
                    for alert in report.alerts:
                        self.log.append(f"{day} {source}: {alert.detail}")
            except NotYetAvailableError as exc:
                self.log.append(str(exc))
                continue
            self._fetched.add((source, day))

    def _is_session(self, day: date) -> bool:
        sessions = self.trader.market.sessions(ist_datetime(day, self.trader.decision_time))
        return bool(sessions) and sessions[-1] == day

    async def step(self) -> list[DayRecord]:
        now = self.clock()
        done: list[DayRecord] = []
        due = self._due(now)
        for day in due:
            await self._ingest(day)
        self.reader.refresh()
        sessions = [d for d in due if self._is_session(d)]
        for day in due:
            if day not in sessions:
                if any(s > day for s in sessions):
                    self.log.append(f"{day}: no session (holiday)")
                    continue
                self.log.append(f"{day}: waiting for EOD files")
                break
            record = await self.trader.run_day(day)
            done.append(record)
            self.checkpoints.save(self.trader.checkpoint(self.clock()))
            self.write_dashboard()
            if self.notifications is not None:
                self.notifications.daily(record, self.limits.capital_inr)
        await self.data.check_staleness()
        await self.trader.bus.drain()
        return done

    def readiness(self) -> ReadinessReport:
        return assess(
            records=self.trader.records,
            book=self.trader.execution.book,
            signer=self.signer,
            tca=self.trader.tca.results,
            closed_trips=self.trader.attribution.closed_trips if self.trader.attribution else (),
            criteria=self.criteria,
            limits=self.limits,
            now=self.clock(),
        )

    def write_dashboard(self) -> None:
        if self.dashboard_path is None:
            return
        data = collect(
            title="Paper trading monitor",
            stage=self.stage,
            generated_at=self.clock(),
            records=self.trader.records,
            book=self.trader.execution.book,
            scores=self.trader.attribution.scores if self.trader.attribution else [],
            tca=self.trader.tca.results,
            limits=self.limits,
            starting_equity=self.limits.capital_inr,
            readiness=self.readiness(),
        )
        self.dashboard_path.parent.mkdir(parents=True, exist_ok=True)
        tmp = self.dashboard_path.with_suffix(".tmp")
        tmp.write_text(render(data), encoding="utf-8")
        tmp.replace(self.dashboard_path)
