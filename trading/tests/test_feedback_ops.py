"""Attribution, notifications and the readiness report."""

from __future__ import annotations

from collections import Counter
from datetime import UTC, datetime
from decimal import Decimal
from typing import Any
from uuid import uuid4

import pytest

from aitrader.config import load_risk_limits, load_system_config
from aitrader.execution.orderbook import InMemoryOrderBook
from aitrader.feedback.attribution import AttributionAgent
from aitrader.notify import MemoryNotifier, NotificationAgent, TelegramNotifier
from aitrader.paper.readiness import assess, untraced_fills
from aitrader.schemas.envelope import Topic
from aitrader.schemas.feedback import AgentScore
from aitrader.schemas.market import Severity
from aitrader.schemas.orders import Fill, Side
from aitrader.schemas.risk import AlertType, RiskAlert
from tests.chain_helpers import KEY, market_store
from tests.execution_helpers import XYZ, run
from tests.test_paper_trading import build

NOW = datetime(2026, 10, 23, 14, 0, tzinfo=UTC)


@pytest.fixture(scope="module")
def attributed() -> Any:
    store, days = market_store()
    trader = build(store, days)
    trader.attribution = AttributionAgent(trader.market, trader.cycle.rr.stats, trader.cycle.credibility)
    trader.attribution.attach(trader.bus)
    before = {s: trader.cycle.rr.stats.win_rate(s)[1] for s in trader.cycle.rr.stats._r}
    run(trader.run(days[-90:]))
    return trader, before


def test_closed_trades_feed_realised_r_into_setup_statistics(attributed: Any) -> None:
    trader, before = attributed
    trips = trader.attribution.closed_trips
    assert trips, "the trending market should close some trades"
    with_setup = [t for t in trips if t.setup is not None and t.r_multiple is not None]
    assert with_setup
    added = Counter(t.setup for t in with_setup)
    for setup, n in added.items():
        assert trader.cycle.rr.stats.win_rate(setup)[1] == before.get(setup, 0) + n
    for t in with_setup:  # a stop-out is about -1R, a target hit is positive
        assert -3 < t.r_multiple < 10


def test_scores_are_published_and_drive_boss_credibility(attributed: Any) -> None:
    trader, _ = attributed
    scores = trader.attribution.scores
    assert {s.agent_id.value for s in scores} >= {"chart", "trend"}
    for s in scores:
        assert 0 < s.credibility < 1 and s.sample_size > 0
        assert trader.cycle.credibility[s.agent_id] == s.credibility
    published = run(trader.bus.replay([Topic.FEEDBACK_SCORES]))
    assert published and all(isinstance(e.payload, AgentScore) for e in published)


def test_signals_are_scored_only_after_their_horizon(attributed: Any) -> None:
    trader, _ = attributed
    state = trader.attribution.state()
    last = trader.records[-1].day
    assert state.pending, "the latest signals cannot have an outcome yet"
    assert all(p.at.date() >= trader.records[-6].day for p in state.pending)
    assert all(s.signal.at.date() < last for s in state.scored)


def test_attribution_state_round_trips_through_a_checkpoint(attributed: Any) -> None:
    trader, _ = attributed
    state = trader.attribution.state()
    clone = AttributionAgent(trader.market, type(trader.cycle.rr.stats)(), {})
    clone.restore(type(state).model_validate_json(state.model_dump_json()))
    assert clone.state() == state


# ---------------------------------------------------------------------- notifications


def alert(
    severity: Severity = Severity.CRITICAL, freeze: bool = True, detail: str = "broker down"
) -> RiskAlert:
    return RiskAlert(
        alert_id=uuid4(),
        alert_type=AlertType.BROKER_DISCONNECT,
        severity=severity,
        freeze_new_orders=freeze,
        detail=detail,
    )


def test_urgent_alerts_are_sent_once_and_warnings_wait_for_the_summary() -> None:
    notifier = MemoryNotifier()
    agent = NotificationAgent(notifier)
    critical = alert()
    agent.alert(critical)
    agent.alert(critical)
    agent.alert(alert(Severity.WARNING, freeze=False))
    released = critical.model_copy(
        update={"alert_id": uuid4(), "clears_alert_id": critical.alert_id, "freeze_new_orders": False}
    )
    agent.alert(released)
    assert len(notifier.messages) == 2
    assert notifier.messages[0].startswith("CRITICAL broker disconnect") and "frozen" in notifier.messages[0]
    assert notifier.messages[1].startswith("RELEASED")


def test_telegram_sends_plain_text_retries_and_never_leaks_the_token() -> None:
    calls: list[tuple[str, dict[str, str]]] = []

    def flaky(url: str, fields: dict[str, str]) -> dict[str, object]:
        calls.append((url, fields))
        if len(calls) == 1:
            raise OSError(f"connection reset while posting to {url}")
        return {"ok": True}

    telegram = TelegramNotifier("123:SECRET", "42", transport=flaky)
    assert telegram.send("<b>x</b>" * 1000)
    url, fields = calls[-1]
    assert url.endswith("/sendMessage") and "parse_mode" not in fields
    assert len(fields["text"]) <= 4096 and fields["chat_id"] == "42"

    def down(url: str, fields: dict[str, str]) -> dict[str, object]:
        raise OSError(f"cannot reach {url}")

    broken = TelegramNotifier("123:SECRET", "42", transport=down)
    assert not broken.send("hello")
    assert broken.failures == 1 and broken.last_error and "SECRET" not in broken.last_error


# ---------------------------------------------------------------------- readiness


def test_readiness_reports_each_criterion(attributed: Any) -> None:
    trader, _ = attributed
    report = assess(
        records=trader.records,
        book=trader.execution.book,
        signer=trader.execution.signer,
        tca=trader.tca.results,
        closed_trips=trader.attribution.closed_trips,
        criteria=load_system_config().rollout.paper_exit,
        limits=load_risk_limits(),
        now=NOW,
    )
    by_name = {r.name: r for r in report.results}
    assert not report.ready  # 90 sessions but too few trades and TCA samples for promotion
    assert by_name["Sessions traded"].passed
    assert by_name["Days with a reconciliation mismatch"].passed
    assert by_name["Fills traced to a verified approval token"].passed
    assert not by_name["Closed trades (measured edge)"].passed


def test_a_fill_without_a_verified_instruction_is_flagged() -> None:
    from aitrader.agents.risk.tokens import TokenSigner

    book = InMemoryOrderBook()
    book.add_fill(
        Fill(
            instruction_id=uuid4(),
            child_order_id="Arogue",
            broker_trade_id="T1",
            instrument=XYZ,
            side=Side.BUY,
            quantity=1,
            price=Decimal("100"),
            filled_at=NOW,
            arrival_price=Decimal("100"),
            slippage_bps=0.0,
        )
    )
    assert untraced_fills(book, TokenSigner(KEY)) == ["T1: no instruction"]
