"""Data agent: ingest, validate, timestamp, store point-in-time, publish, and raise
data-quality alerts. Deterministic; no LLM.

``MarketData`` is the read side every other component uses: bars as of a point in time,
back-adjusted only for corporate actions known by then.
"""

from __future__ import annotations

from collections.abc import Callable, Iterable, Sequence
from dataclasses import dataclass, field
from datetime import date, datetime, time, timedelta
from decimal import Decimal
from uuid import NAMESPACE_URL, UUID, uuid4, uuid5

from aitrader.agents.data.adjust import (
    AdjustmentFactor,
    UnsupportedAdjustmentError,
    apply_factors,
    factor_for,
)
from aitrader.agents.data.calendar import daily_bar_window, ist_date, ist_datetime, parse_hhmm
from aitrader.agents.data.quality import is_stale, missing_sessions, return_outliers
from aitrader.agents.data.sources.nse import (
    ParsedBars,
    SourceFormatError,
    parse_index_close,
    parse_legacy_cm_bhavcopy,
    parse_participant_oi,
    parse_udiff_bhavcopy,
)
from aitrader.agents.data.universe import Universe
from aitrader.bus.interface import MessageBus
from aitrader.config.settings import DataConfig
from aitrader.schemas.base import AgentId, Exchange, Instrument, InstrumentType, Priority, Segment, Timeframe
from aitrader.schemas.envelope import Envelope, Topic
from aitrader.schemas.market import (
    Bar,
    CorporateAction,
    CorporateActionType,
    DataQualityAlert,
    DataQualityIssue,
    Severity,
)
from aitrader.schemas.risk import AlertType, RiskAlert
from aitrader.storage.pit import CachedPitReader, PitReader, PitRecord, PitStore

BARS = "bars"
CORPORATE_ACTIONS = "corporate_actions"
PARTICIPANT_OI = "participant_oi"
_MSG_NAMESPACE = uuid5(NAMESPACE_URL, "aitrader/data-agent")


def bar_entity(instrument: Instrument, timeframe: Timeframe) -> str:
    return f"{instrument.key}|{timeframe.value}"


def index_instrument(symbol: str) -> Instrument:
    return Instrument(
        exchange=Exchange.NSE, segment=Segment.INDEX, symbol=symbol, instrument_type=InstrumentType.INDEX
    )


@dataclass
class BarSeries:
    bars: list[Bar]
    # Actions known as of the read that could not be adjusted automatically; consumers
    # should treat prices around these dates as unreliable.
    unadjusted_actions: list[str] = field(default_factory=list)


class MarketData:
    def __init__(self, store: PitReader, calendar_reference: str = "NIFTY_50") -> None:
        self._store = store
        self._reference = calendar_reference
        self._parsed: dict[tuple[str, datetime, datetime], Bar] = {}

    @classmethod
    def for_backtest(cls, store: PitStore, calendar_reference: str = "NIFTY_50") -> MarketData:
        """Same answers, served from memory: for replaying a store that is no longer written to."""
        return cls(CachedPitReader(store), calendar_reference)

    def raw_bars(
        self,
        instrument: Instrument,
        timeframe: Timeframe,
        as_of: datetime,
        start: datetime | None = None,
        end: datetime | None = None,
    ) -> list[Bar]:
        end = min(end, as_of) if end is not None else as_of
        rows = self._store.read_as_of(
            BARS, as_of, entities=[bar_entity(instrument, timeframe)], start=start, end=end
        )
        out = []
        for r in rows:
            key = (r.entity, r.event_time, r.available_at)
            bar = self._parsed.get(key)
            if bar is None:
                bar = self._parsed[key] = Bar.model_validate(r.payload)
            out.append(bar)
        return out

    def corporate_actions(self, instrument: Instrument, as_of: datetime) -> list[CorporateAction]:
        rows = self._store.read_as_of(CORPORATE_ACTIONS, as_of, entities=[instrument.key])
        return sorted((CorporateAction.model_validate(r.payload) for r in rows), key=lambda a: a.ex_date)

    def bars(
        self,
        instrument: Instrument,
        timeframe: Timeframe,
        as_of: datetime,
        start: datetime | None = None,
        end: datetime | None = None,
    ) -> BarSeries:
        """Bars visible at ``as_of``, back-adjusted for actions known at ``as_of``."""
        raw = self.raw_bars(instrument, timeframe, as_of, start, end)
        if instrument.instrument_type is not InstrumentType.EQUITY or not raw:
            return BarSeries(raw)
        factors: list[AdjustmentFactor] = []
        problems: list[str] = []
        as_of_date = ist_date(as_of)

        def close_before(ex_date: date) -> Decimal | None:
            cutoff = ist_datetime(ex_date, time(0, 0))
            prior = self.raw_bars(instrument, Timeframe.D1, as_of, end=min(cutoff, as_of))
            return prior[-1].close if prior else None

        for action in self.corporate_actions(instrument, as_of):
            if action.ex_date > as_of_date:
                continue
            try:
                factor = factor_for(action, close_before)
            except UnsupportedAdjustmentError as exc:
                problems.append(str(exc))
                continue
            if factor is not None:
                factors.append(factor)
        return BarSeries(apply_factors(raw, factors, as_of_date), problems)

    def derivatives(self, symbol: str, as_of: datetime) -> list[Instrument]:
        """Futures and options on ``symbol`` with daily bars known at ``as_of``."""
        prefix = f"NSE:{symbol}:"
        out = []
        for entity in self._store.entities(BARS, as_of):
            if entity.startswith(prefix) and entity.endswith("|1d"):
                bars = self._store.read_as_of(BARS, as_of, entities=[entity])
                if bars:
                    out.append(Bar.model_validate(bars[-1].payload).instrument)
        return out

    def sessions(self, as_of: datetime, start: datetime | None = None) -> list[date]:
        """Trading sessions = days on which the calendar reference index has a bar."""
        bars = self.raw_bars(index_instrument(self._reference), Timeframe.D1, as_of, start=start)
        return [ist_date(b.bar_end) for b in bars]


@dataclass
class IngestReport:
    source: str
    trade_date: date | None
    parsed: int
    written: int
    published: int
    rejects: list[tuple[int, str]]
    alerts: list[DataQualityAlert]


class NotYetAvailableError(RuntimeError):
    """Data was ingested before its publication time; it must not be used yet."""


PARSERS: dict[str, Callable[[str, frozenset[str]], ParsedBars]] = {
    "nse_cm_bhavcopy": lambda text, series: parse_udiff_bhavcopy(text, cash_series=series),
    "nse_fo_bhavcopy": lambda text, series: parse_udiff_bhavcopy(text, cash_series=series),
    "nse_index_close": lambda text, _series: parse_index_close(text),
    "nse_cm_bhavcopy_legacy": lambda text, series: parse_legacy_cm_bhavcopy(text, cash_series=series),
}


class DataAgent:
    def __init__(
        self, store: PitStore, bus: MessageBus, config: DataConfig, clock: Callable[[], datetime]
    ) -> None:
        self._store = store
        self._bus = bus
        self._config = config
        self._clock = clock
        self.market = MarketData(store, config.calendar_reference)
        self.universe = Universe(store)

    def available_at(self, source: str, trade_date: date) -> datetime:
        return ist_datetime(trade_date, parse_hhmm(self._config.availability_ist[source]))

    async def ingest_eod(self, source: str, text: str, *, publish: bool = True) -> IngestReport:
        """Parse, validate and store one EOD file. ``publish=False`` (historical backfill)
        stores without broadcasting each bar; the caller audits the batch instead."""
        if source not in PARSERS:
            raise KeyError(f"unknown source {source}")
        try:
            parsed = PARSERS[source](text, frozenset(self._config.cash_series))
        except SourceFormatError as exc:
            alert = await self._alert(
                DataQualityIssue.SCHEMA_VIOLATION, Severity.CRITICAL, source, f"format drift: {exc}"
            )
            return IngestReport(source, None, 0, 0, 0, [], [alert])
        alerts: list[DataQualityAlert] = []
        if parsed.trade_date is None:
            alerts.append(
                await self._alert(
                    DataQualityIssue.GAP, Severity.CRITICAL, source, "file contained no usable rows"
                )
            )
            return IngestReport(source, None, 0, 0, 0, parsed.rejects, alerts)
        available_at = self.available_at(source, parsed.trade_date)
        now = self._clock()
        if now < available_at:
            raise NotYetAvailableError(
                f"{source} for {parsed.trade_date} is usable from {available_at.isoformat()}"
            )
        records = [
            PitRecord(
                dataset=BARS,
                entity=bar_entity(bar.instrument, bar.timeframe),
                event_time=bar.bar_end,
                available_at=available_at,
                payload=bar.model_dump(mode="json"),
            )
            for bar in parsed.bars
        ]
        written = self._store.write(records, ingested_at=now)
        published = 0
        if written and publish:
            for bar, record in zip(parsed.bars, records, strict=True):
                if (
                    bar.instrument.instrument_type is InstrumentType.OPTION
                    and not self._config.publish_option_bars
                ):
                    continue
                await self._bus.publish(
                    Envelope(
                        message_id=self._message_id(record),
                        topic=Topic.DATA_BARS,
                        agent_id=AgentId.DATA,
                        event_time=bar.bar_end,
                        available_at=available_at,
                        produced_at=now,
                        payload=bar,
                    )
                )
                published += 1
        if parsed.rejects:
            sample = "; ".join(f"line {n}: {why}" for n, why in parsed.rejects[:3])
            alerts.append(
                await self._alert(
                    DataQualityIssue.SCHEMA_VIOLATION,
                    Severity.WARNING,
                    source,
                    f"{len(parsed.rejects)} rows rejected ({sample})",
                )
            )
        return IngestReport(
            source, parsed.trade_date, len(parsed.bars), written, published, parsed.rejects, alerts
        )

    async def ingest_participant_oi(self, text: str, trade_date: date) -> int:
        """Store one day's participant-wise OI (read by the smart money engine; not broadcast)."""
        try:
            rows = parse_participant_oi(text, trade_date)
        except SourceFormatError as exc:
            await self._alert(
                DataQualityIssue.SCHEMA_VIOLATION, Severity.WARNING, "nse_participant_oi", str(exc)
            )
            return 0
        available_at = self.available_at("nse_participant_oi", trade_date)
        now = self._clock()
        if now < available_at:
            raise NotYetAvailableError(
                f"participant OI for {trade_date} is usable from {available_at.isoformat()}"
            )
        _, close = daily_bar_window(trade_date)
        records = [
            PitRecord(
                dataset=PARTICIPANT_OI,
                entity=row.participant,
                event_time=close,
                available_at=available_at,
                payload=row.model_dump(mode="json"),
            )
            for row in rows
        ]
        return self._store.write(records, ingested_at=now)

    async def ingest_corporate_actions(self, actions: Iterable[tuple[CorporateAction, datetime]]) -> int:
        """Store and publish announced corporate actions. ``available_at`` is the
        announcement time at the exchange, never the time we scraped it."""
        now = self._clock()
        pending = list(actions)
        records = [
            PitRecord(
                dataset=CORPORATE_ACTIONS,
                entity=action.instrument.key,
                event_time=action.announced_at,
                available_at=available_at,
                payload=action.model_dump(mode="json"),
            )
            for action, available_at in pending
        ]
        written = self._store.write(records, ingested_at=now)
        manual = {CorporateActionType.RIGHTS, CorporateActionType.MERGER, CorporateActionType.DEMERGER}
        for (action, available_at), record in zip(pending, records, strict=True):
            if available_at > now:
                continue  # stored but not yet public; it becomes visible to as-of reads later
            await self._bus.publish(
                Envelope(
                    message_id=self._message_id(record),
                    topic=Topic.DATA_CORPORATE_ACTIONS,
                    agent_id=AgentId.DATA,
                    event_time=action.announced_at,
                    available_at=available_at,
                    produced_at=now,
                    payload=action,
                )
            )
            if action.action_type in manual:
                await self._alert(
                    DataQualityIssue.SOURCE_DISAGREEMENT,
                    Severity.WARNING,
                    "corporate_actions",
                    f"{action.action_type} for {action.instrument.symbol} ex {action.ex_date}: "
                    "needs manual adjustment terms",
                    action.instrument,
                )
        return written

    async def check_quality(
        self, instruments: Sequence[Instrument], as_of: datetime, lookback_days: int = 120
    ) -> list[DataQualityAlert]:
        """Gap and outlier checks over the recent window for the given cash instruments."""
        start = as_of - timedelta(days=lookback_days)
        sessions = self.market.sessions(as_of, start=start)
        alerts = []
        for instrument in instruments:
            security = self.universe.security(instrument.symbol, as_of)
            series = self.market.bars(instrument, Timeframe.D1, as_of, start=start)
            dates = [ist_date(b.bar_end) for b in series.bars]
            gaps = missing_sessions(
                sessions,
                set(dates),
                listed_on=security.listed_on if security else None,
                delisted_on=security.delisted_on if security else None,
            )
            if gaps:
                alerts.append(
                    await self._alert(
                        DataQualityIssue.GAP,
                        Severity.WARNING,
                        "bars",
                        f"{len(gaps)} missing sessions, latest {gaps[-1].isoformat()}",
                        instrument,
                    )
                )
            exempt = {a.ex_date for a in self.market.corporate_actions(instrument, as_of)}
            outliers = return_outliers(
                dates,
                [float(b.close) for b in series.bars],
                exempt_dates=exempt,
                threshold=self._config.outlier_robust_z,
            )
            if outliers:
                alerts.append(
                    await self._alert(
                        DataQualityIssue.OUTLIER,
                        Severity.WARNING,
                        "bars",
                        "return outliers on " + ", ".join(d.isoformat() for d in outliers[-5:]),
                        instrument,
                    )
                )
        return alerts

    async def check_staleness(self) -> DataQualityAlert | None:
        """Primary EOD feed freshness, measured on the calendar reference index."""
        now = self._clock()
        entity = bar_entity(index_instrument(self._config.calendar_reference), Timeframe.D1)
        last = self._store.latest_available_at(BARS, entity)
        visible = last if last is not None and last <= now else None
        if is_stale(visible, now, timedelta(hours=self._config.stale_critical_hours)):
            return await self._alert(
                DataQualityIssue.STALE_FEED, Severity.CRITICAL, "nse_eod", "EOD feed is stale"
            )
        if is_stale(visible, now, timedelta(hours=self._config.stale_warning_hours)):
            return await self._alert(
                DataQualityIssue.STALE_FEED, Severity.WARNING, "nse_eod", "EOD feed is late"
            )
        return None

    @staticmethod
    def _message_id(record: PitRecord) -> UUID:
        # Deterministic ids: re-ingesting the same file re-publishes identical message ids,
        # which subscribers deduplicate.
        parts = (
            record.dataset,
            record.entity,
            record.event_time.isoformat(),
            record.available_at.isoformat(),
        )
        key = "|".join(parts) + "|" + record.payload_json
        return uuid5(_MSG_NAMESPACE, key)

    async def _alert(
        self,
        issue: DataQualityIssue,
        severity: Severity,
        source: str,
        detail: str,
        instrument: Instrument | None = None,
    ) -> DataQualityAlert:
        now = self._clock()
        alert = DataQualityAlert(
            issue=issue,
            severity=severity,
            source=source,
            instrument=instrument,
            detail=detail[:500],
            detected_at=now,
        )
        await self._bus.publish(
            Envelope(
                topic=Topic.DATA_QUALITY,
                agent_id=AgentId.DATA,
                event_time=now,
                available_at=now,
                produced_at=now,
                priority=Priority.HIGH if severity is Severity.CRITICAL else Priority.NORMAL,
                payload=alert,
            )
        )
        if severity is Severity.CRITICAL:
            await self._bus.publish(
                Envelope(
                    topic=Topic.RISK_ALERTS,
                    agent_id=AgentId.DATA,
                    event_time=now,
                    available_at=now,
                    produced_at=now,
                    priority=Priority.INTERRUPT,
                    payload=RiskAlert(
                        alert_id=uuid4(),
                        alert_type=AlertType.DATA_FAILURE,
                        severity=Severity.CRITICAL,
                        freeze_new_orders=True,
                        detail=f"{source}: {detail}"[:500],
                    ),
                )
            )
        return alert
