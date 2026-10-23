"""Monitoring dashboard: content, escaping of untrusted text, no external resources."""

from __future__ import annotations

import re
from datetime import UTC, datetime
from uuid import uuid4

from aitrader.config import load_risk_limits
from aitrader.execution.orderbook import InMemoryOrderBook
from aitrader.feedback.attribution import AttributionAgent
from aitrader.monitoring.dashboard import collect, render
from aitrader.schemas.market import Severity
from aitrader.schemas.risk import AlertType, RiskAlert
from tests.chain_helpers import market_store
from tests.execution_helpers import run
from tests.test_paper_trading import build

GENERATED = datetime(2026, 10, 23, 12, 0, tzinfo=UTC)


def test_dashboard_from_a_paper_run_is_complete_and_safe() -> None:
    store, days = market_store()
    trader = build(store, days)
    trader.attribution = AttributionAgent(trader.market, trader.cycle.rr.stats, trader.cycle.credibility)
    trader.attribution.attach(trader.bus)
    records = run(trader.run(days[-30:]))
    hostile = RiskAlert(
        alert_id=uuid4(),
        alert_type=AlertType.DATA_FAILURE,
        severity=Severity.CRITICAL,
        freeze_new_orders=True,
        detail='<script>alert("x")</script> & <img src=x onerror=1>',
    )
    records[-1] = records[-1].model_copy(update={"alerts": (*records[-1].alerts, hostile)})
    data = collect(
        title="Paper <monitor>",
        stage="paper",
        generated_at=GENERATED,
        records=records,
        book=trader.execution.book,
        scores=trader.attribution.scores,
        tca=trader.tca.results,
        limits=load_risk_limits(),
        starting_equity=1_000_000,
    )
    page = render(data)
    for section in (
        "Equity",
        "Drawdown from peak",
        "Exposure",
        "broker-held stops",
        "Agent health",
        "TCA",
        "Alerts",
    ):
        assert section in page
    assert "<script>alert" not in page and "&lt;script&gt;alert" in page
    assert "<img src=x" not in page
    assert "Paper &lt;monitor&gt;" in page
    assert not re.search(r'(src|href)\s*=\s*"?https?:', page)  # self-contained: no external resources
    assert "prefers-color-scheme:dark" in page and 'data-theme="dark"' in page
    assert len(data.positions) == len({k for k, v in records[-1].positions.items() if v})
    assert all(p.protected == abs(p.quantity) for p in data.positions)


def test_empty_dashboard_renders() -> None:
    page = render(
        collect(
            title="Paper",
            stage="paper",
            generated_at=GENERATED,
            records=[],
            book=InMemoryOrderBook(),
            scores=[],
            tca=[],
            limits=load_risk_limits(),
            starting_equity=1_000_000,
        )
    )
    assert "No sessions recorded yet." in page and "No open positions." in page
