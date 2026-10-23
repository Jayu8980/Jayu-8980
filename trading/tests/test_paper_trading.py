"""Paper trading end to end: the decision chain, the bus and the real execution path."""

from __future__ import annotations

from datetime import date, time
from pathlib import Path
from typing import Any

import pytest

from aitrader.agents.data.agent import MarketData
from aitrader.agents.data.calendar import ist_datetime
from aitrader.backtest.strategies import equity
from aitrader.config import load_risk_limits, load_system_config
from aitrader.execution.agent import ExecutionAgent
from aitrader.execution.checks import MarketClock, RateLimiter
from aitrader.execution.killswitch import KillSwitch
from aitrader.execution.orderbook import InMemoryOrderBook, OrderBook, net_positions
from aitrader.execution.paper import PaperBroker
from aitrader.feedback.tca import TcaAgent
from aitrader.paper.runner import DayRecord, PaperTrader, SimClock
from aitrader.schemas.envelope import Topic
from aitrader.schemas.orders import InstructionIntent
from aitrader.state import InMemoryStateStore
from aitrader.storage import CachedPitReader, PitStore
from tests.chain_helpers import SYMBOLS, market_store, wire
from tests.execution_helpers import no_wait, run

DAYS = 45


def build(
    store: PitStore,
    days: list[date],
    kill_file: Path | None = None,
    *,
    book: OrderBook | None = None,
    **overrides: Any,
) -> PaperTrader:
    """The paper trader on the in-memory stack; ``overrides`` swap in durable components
    (bus, state, risk, audit) for the backend tests."""
    audit = overrides.pop("audit", None)
    reader = overrides.pop("reader", None) or CachedPitReader(store)
    cycle, signer, state, default_audit = wire(store, reader=reader, **overrides)
    audit = audit or default_audit
    limits = load_risk_limits()
    market = MarketData(reader)
    clock = SimClock(ist_datetime(days[0], time(8, 0)))
    broker = PaperBroker(market, clock, cash=1_000_000)
    book = book or InMemoryOrderBook()
    execution = ExecutionAgent(
        broker=broker,
        signer=signer,
        state=state,
        book=book,
        bus=cycle.bus,
        limits=limits,
        broker_config=load_system_config().broker,
        market_clock=MarketClock(sessions=days),
        rate_limiter=RateLimiter(1000),
        retry_delay=no_wait,
    )
    return PaperTrader(
        cycle=cycle,
        risk=cycle.risk,
        execution=execution,
        broker=broker,
        kill_switch=KillSwitch(cycle.bus, state, kill_file),
        tca=TcaAgent(cycle.bus, book, limits.liquidity.assumed_spread_bps),
        bus=cycle.bus,
        state=state,
        market=market,
        audit=audit,
        clock=clock,
        instruments=[equity(s) for s in SYMBOLS],
    )


def fingerprint_of(trader: PaperTrader) -> list[tuple[object, ...]]:
    return [
        (f.instrument.key, f.side, f.quantity, f.price, f.filled_at) for f in trader.execution.book.fills()
    ]


@pytest.fixture(scope="module")
def baseline() -> tuple[PaperTrader, list[DayRecord]]:
    store, days = market_store()
    trader = build(store, days)
    return trader, run(trader.run(days[-DAYS:]))


def test_paper_run_trades_and_stays_reconciled(baseline: tuple[PaperTrader, list[DayRecord]]) -> None:
    trader, records = baseline
    fills = trader.execution.book.fills()
    assert fills, "the trending synthetic market should produce trades"
    assert all(r.reconciliation is not None and r.reconciliation.matched for r in records)
    assert not any(r.frozen for r in records)
    assert trader.audit.verify_chain()
    assert records[-1].equity == pytest.approx(trader.broker.equity())


def test_every_fill_traces_to_a_verified_instruction(baseline: tuple[PaperTrader, list[DayRecord]]) -> None:
    trader, _ = baseline
    book = trader.execution.book
    signer = trader.execution.signer
    by_id = {r.instruction.instruction_id: r.instruction for r in book.instructions()}
    for fill in book.fills():
        instruction = by_id[fill.instruction_id]
        signer.verify(
            instruction, instruction.approval.issued_at, InMemoryStateStore()
        )  # signature, digest, expiry
    envelopes = run(trader.bus.replay(list(Topic)))
    ids = {e.message_id: e for e in envelopes}
    orders = [e for e in envelopes if e.topic is Topic.ORDERS_INSTRUCTIONS]
    assert orders
    for env in orders:
        cause_topics = {ids[c].topic for c in env.causation_ids if c in ids}
        assert Topic.RISK_APPROVAL in cause_topics


def test_open_positions_are_always_covered_by_broker_stops(
    baseline: tuple[PaperTrader, list[DayRecord]],
) -> None:
    trader, records = baseline
    book = trader.execution.book
    positions = net_positions(book.fills())
    assert positions == {k: v for k, v in records[-1].positions.items() if v}
    for key, qty in positions.items():
        covered = sum(p.quantity for p in book.protections() if p.live and p.instrument.key == key)
        assert covered == abs(qty)


def test_paper_trading_is_reproducible(baseline: tuple[PaperTrader, list[DayRecord]]) -> None:
    trader, _ = baseline
    store, days = market_store()
    again = build(store, days)
    run(again.run(days[-DAYS:]))

    assert fingerprint_of(again) == fingerprint_of(trader)


def test_kill_file_stops_new_entries_mid_run(tmp_path: Path) -> None:
    store, days = market_store()
    kill = tmp_path / "KILL"
    trader = build(store, days, kill)
    window = days[-DAYS:]
    run(trader.run(window[:20]))
    kill.write_text("operator: halt", encoding="utf-8")
    later = run(trader.run(window[20:30]))
    assert all(r.frozen for r in later)
    book = trader.execution.book
    accepted_after = [
        r for r in book.instructions() if r.received_at.date() >= window[20] and r.status.value == "accepted"
    ]
    assert all(r.instruction.intent is InstructionIntent.CLOSE for r in accepted_after)
    assert trader.kill_switch.engaged is not None


def test_flatten_kill_exits_every_position_through_signed_instructions(tmp_path: Path) -> None:
    store, days = market_store()
    kill = tmp_path / "KILL"
    trader = build(store, days, kill)
    window = days[-DAYS:]
    held_before = run(trader.run(window[:25]))[-1].positions
    assert any(held_before.values()), "needs open positions to flatten"
    kill.write_text("FLATTEN now", encoding="utf-8")
    after = run(trader.run(window[25:28]))
    assert not any(after[-1].positions.values())
    assert all(r.frozen for r in after)
    exits = [r for r in trader.execution.book.instructions() if r.received_at.date() >= window[25]]
    assert exits and all(r.instruction.intent is InstructionIntent.CLOSE for r in exits)
    assert all(r.reconciliation is not None and r.reconciliation.matched for r in after)
