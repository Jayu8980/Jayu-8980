import asyncio
from datetime import date, datetime, timedelta
from pathlib import Path

import numpy as np
import pytest

from aitrader.agents.data.agent import NotYetAvailableError
from aitrader.agents.data.calendar import ist_datetime
from aitrader.schemas.base import InstrumentType, Priority, Timeframe
from aitrader.schemas.envelope import Topic
from aitrader.schemas.market import DataQualityIssue, Severity
from aitrader.schemas.risk import AlertType
from tests.data_helpers import NIFTY, RELIANCE, Harness, eod, make_bar, store_bar, weekdays

FIXTURES = Path(__file__).parent / "fixtures"
CM = (FIXTURES / "BhavCopy_NSE_CM_0_0_0_20261021_F_0000.csv").read_text()
FO = (FIXTURES / "BhavCopy_NSE_FO_0_0_0_20261021_F_0000.csv").read_text()
IDX = (FIXTURES / "ind_close_all_21102026.csv").read_text()
TRADE_DAY = date(2026, 10, 21)
AFTER_PUBLICATION = eod(TRADE_DAY) + timedelta(minutes=5)


def test_ingest_stores_publishes_and_reports_rejects() -> None:
    h = Harness(AFTER_PUBLICATION)
    report = asyncio.run(h.agent.ingest_eod("nse_cm_bhavcopy", CM))
    assert (report.parsed, report.written, report.published) == (3, 3, 3)
    bars = h.topics(Topic.DATA_BARS)
    assert {e.payload.instrument.symbol for e in bars} == {"RELIANCE", "TCS", "M&M"}  # type: ignore[union-attr]
    assert all(e.available_at == eod(TRADE_DAY) and e.event_time < e.available_at for e in bars)
    [alert] = h.topics(Topic.DATA_QUALITY)
    assert alert.payload.issue is DataQualityIssue.SCHEMA_VIOLATION  # type: ignore[union-attr]
    assert h.audit.verify_chain()


def test_data_cannot_be_ingested_before_its_publication_time() -> None:
    h = Harness(ist_datetime(TRADE_DAY, datetime.min.time().replace(hour=18)))
    with pytest.raises(NotYetAvailableError):
        asyncio.run(h.agent.ingest_eod("nse_cm_bhavcopy", CM))
    assert h.store.count() == 0 and h.published == []


def test_reingesting_the_same_file_is_harmless() -> None:
    h = Harness(AFTER_PUBLICATION)
    asyncio.run(h.agent.ingest_eod("nse_cm_bhavcopy", CM))
    first = len(h.topics(Topic.DATA_BARS))
    again = asyncio.run(h.agent.ingest_eod("nse_cm_bhavcopy", CM))
    assert again.written == 0 and again.published == 0
    assert len(h.topics(Topic.DATA_BARS)) == first


def test_option_bars_are_stored_but_not_broadcast() -> None:
    h = Harness(AFTER_PUBLICATION)
    report = asyncio.run(h.agent.ingest_eod("nse_fo_bhavcopy", FO))
    assert report.written == 4 and report.published == 2
    kinds = {e.payload.instrument.instrument_type for e in h.topics(Topic.DATA_BARS)}  # type: ignore[union-attr]
    assert kinds == {InstrumentType.FUTURE}


def test_format_drift_raises_a_freeze() -> None:
    h = Harness(AFTER_PUBLICATION)
    report = asyncio.run(h.agent.ingest_eod("nse_cm_bhavcopy", CM.replace("TckrSymb", "Symbol", 1)))
    assert report.written == 0
    [risk] = h.topics(Topic.RISK_ALERTS)
    assert risk.priority is Priority.INTERRUPT
    assert risk.payload.alert_type is AlertType.DATA_FAILURE and risk.payload.freeze_new_orders  # type: ignore[union-attr]


def test_staleness_escalates_from_warning_to_freeze() -> None:
    h = Harness(AFTER_PUBLICATION)
    asyncio.run(h.agent.ingest_eod("nse_index_close", IDX))
    assert asyncio.run(h.agent.check_staleness()) is None
    h.clock.now = AFTER_PUBLICATION + timedelta(hours=90)
    late = asyncio.run(h.agent.check_staleness())
    assert late is not None and late.severity is Severity.WARNING and not h.topics(Topic.RISK_ALERTS)
    h.clock.now = AFTER_PUBLICATION + timedelta(hours=130)
    stale = asyncio.run(h.agent.check_staleness())
    assert stale is not None and stale.severity is Severity.CRITICAL
    assert h.topics(Topic.RISK_ALERTS)


def test_gap_and_outlier_detection() -> None:
    days = weekdays(date(2026, 6, 1), 60)
    h = Harness(eod(days[-1]) + timedelta(hours=1))
    rng = np.random.default_rng(3)
    prices = 1400 * np.exp(np.cumsum(rng.normal(0, 0.012, len(days))))
    for i, d in enumerate(days):
        store_bar(h.store, make_bar(NIFTY, d, 25000 + i))
        if d == days[30]:
            continue  # missing session for RELIANCE
        price = round(float(prices[i]) * (1.5 if d == days[45] else 1.0), 2)  # bad tick on day 45
        store_bar(h.store, make_bar(RELIANCE, d, price))
    alerts = asyncio.run(h.agent.check_quality([RELIANCE], h.clock.now, lookback_days=120))
    issues = {a.issue: a for a in alerts}
    assert days[30].isoformat() in issues[DataQualityIssue.GAP].detail
    assert days[45].isoformat() in issues[DataQualityIssue.OUTLIER].detail


def test_market_data_sessions_come_from_the_reference_index() -> None:
    h = Harness(AFTER_PUBLICATION)
    asyncio.run(h.agent.ingest_eod("nse_index_close", IDX))
    assert h.agent.market.sessions(AFTER_PUBLICATION) == [TRADE_DAY]
    assert h.agent.market.raw_bars(NIFTY, Timeframe.D1, eod(TRADE_DAY) - timedelta(seconds=1)) == []
