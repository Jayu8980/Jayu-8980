"""State store, order book and bus contracts on the durable backends.

Postgres and Redis tests run when AITRADER_TEST_POSTGRES_DSN / AITRADER_TEST_REDIS_URL point
at disposable servers (CI starts both as services); otherwise they are skipped. Each test
works in its own schema or key prefix and removes it afterwards.
"""

from __future__ import annotations

import os
import threading
from collections.abc import Iterator
from datetime import UTC, datetime
from decimal import Decimal
from typing import Any
from uuid import uuid4

import pytest

from aitrader.audit import InMemoryAuditLog
from aitrader.execution.orderbook import (
    ChildKind,
    ChildOrder,
    IllegalTransitionError,
    InMemoryOrderBook,
    InstructionRecord,
    InstructionStatus,
    OrderBook,
    Protection,
)
from aitrader.schemas.base import AgentId, Priority
from aitrader.schemas.envelope import Envelope, Topic
from aitrader.schemas.feedback import AgentHeartbeat, HealthStatus
from aitrader.schemas.market import Severity
from aitrader.schemas.orders import Fill, OrderState, Product, Side
from aitrader.schemas.regime import RegimeLabel
from aitrader.schemas.risk import AlertType, RiskAlert
from aitrader.state import FreezeClearRefusedError, InMemoryStateStore, StateStore
from tests.execution_helpers import XYZ, rig, run

PG_DSN = os.environ.get("AITRADER_TEST_POSTGRES_DSN")
REDIS_URL = os.environ.get("AITRADER_TEST_REDIS_URL")
NOW = datetime(2026, 10, 22, 14, 30, tzinfo=UTC)

needs_pg = pytest.mark.skipif(not PG_DSN, reason="AITRADER_TEST_POSTGRES_DSN not set")
needs_redis = pytest.mark.skipif(not REDIS_URL, reason="AITRADER_TEST_REDIS_URL not set")


@pytest.fixture
def pg_schema() -> Iterator[tuple[Any, str]]:
    if not PG_DSN:
        pytest.skip("AITRADER_TEST_POSTGRES_DSN not set")
    from aitrader.state.postgres import connect

    conn = connect(PG_DSN)
    schema = f"t_{uuid4().hex[:12]}"
    yield conn, schema
    conn.execute(f"DROP SCHEMA IF EXISTS {schema} CASCADE")
    conn.close()


@pytest.fixture(params=["memory", "postgres"])
def store(request: pytest.FixtureRequest) -> Iterator[StateStore]:
    if request.param == "memory":
        yield InMemoryStateStore()
        return
    conn, schema = request.getfixturevalue("pg_schema")
    from aitrader.state.postgres import PostgresStateStore

    yield PostgresStateStore(conn, schema)


@pytest.fixture(params=["memory", "postgres"])
def book(request: pytest.FixtureRequest) -> Iterator[OrderBook]:
    if request.param == "memory":
        yield InMemoryOrderBook()
        return
    conn, schema = request.getfixturevalue("pg_schema")
    from aitrader.state.postgres import PostgresOrderBook

    yield PostgresOrderBook(conn, schema)


def test_state_store_contract(store: StateStore) -> None:
    token = uuid4()
    assert store.consume_token(token) and not store.consume_token(token)
    assert store.claim_idempotency_key("k" * 16) and not store.claim_idempotency_key("k" * 16)
    assert not store.freeze_status().frozen
    alert = uuid4()
    store.set_frozen(alert, NOW)
    store.set_frozen(alert, NOW)
    status = store.freeze_status()
    assert status.frozen and status.reason_alert_ids == (alert,) and status.since == NOW
    with pytest.raises(FreezeClearRefusedError):
        store.clear_freeze(alert, cleared_by_human=False)
    store.clear_freeze(alert, cleared_by_human=True)
    assert not store.freeze_status().frozen
    assert store.limit_multiplier() == 1.0
    store.set_limit_multiplier(0.6)
    assert store.limit_multiplier() == 0.6
    with pytest.raises(ValueError):
        store.set_limit_multiplier(0)
    store.set_regime(RegimeLabel.BULL_TREND)
    assert store.current_regime() is RegimeLabel.BULL_TREND


def _child(instruction_id: Any, i: int = 0) -> ChildOrder:
    return ChildOrder(
        child_order_id=f"Achild{i}{instruction_id.hex[:8]}",
        instruction_id=instruction_id,
        kind=ChildKind.ENTRY,
        slice_index=i,
        instrument=XYZ,
        side=Side.BUY,
        quantity=10,
        limit_price=Decimal("101"),
        product=Product.CNC,
        not_before=NOW,
        updated_at=NOW,
    )


def _fill(trade_id: str, side: Side, qty: int) -> Fill:
    return Fill(
        instruction_id=uuid4(),
        child_order_id="Ac",
        broker_trade_id=trade_id,
        instrument=XYZ,
        side=side,
        quantity=qty,
        price=Decimal("100"),
        filled_at=NOW,
        arrival_price=Decimal("100"),
        slippage_bps=0.0,
    )


def test_order_book_contract(book: OrderBook) -> None:
    r = rig()
    instruction = r.instruction(0)
    record = InstructionRecord(
        instruction=instruction, status=InstructionStatus.ACCEPTED, received_at=NOW, arrival_price=100.0
    )
    assert book.add_instruction(record) and not book.add_instruction(record)
    assert book.instruction(instruction.instruction_id) == record
    assert book.instructions() == [record]
    child = _child(instruction.instruction_id)
    book.add_child(child)
    with pytest.raises(ValueError):
        book.add_child(child)
    placed = child.model_copy(update={"state": OrderState.PLACED, "broker_order_id": "B1"})
    book.update_child(placed)
    with pytest.raises(IllegalTransitionError):
        book.update_child(placed.model_copy(update={"state": OrderState.PENDING}))
    filled = placed.model_copy(update={"state": OrderState.FILLED, "filled_quantity": 10})
    book.update_child(filled)
    assert book.child(child.child_order_id) == filled
    assert book.children(instruction.instruction_id) == [filled] and book.children() == [filled]
    protection = Protection(
        protection_id="Aprot1",
        instruction_id=instruction.instruction_id,
        instrument=XYZ,
        side=Side.SELL,
        quantity=10,
        stop_trigger=Decimal("95"),
        stop_limit=Decimal("93.1"),
        product=Product.CNC,
        updated_at=NOW,
    )
    book.put_protection(protection)
    book.put_protection(protection.model_copy(update={"status": "active", "broker_protective_id": "G1"}))
    assert [p.status for p in book.protections()] == ["active"]
    assert book.add_fill(_fill("T1", Side.BUY, 10)) and not book.add_fill(_fill("T1", Side.BUY, 10))
    book.add_fill(_fill("T2", Side.SELL, 4))
    assert [f.broker_trade_id for f in book.fills()] == ["T1", "T2"]


@needs_pg
def test_postgres_single_use_holds_across_concurrent_connections(pg_schema: tuple[Any, str]) -> None:
    from aitrader.state.postgres import PostgresStateStore, connect

    _, schema = pg_schema
    PostgresStateStore(connect(PG_DSN or ""), schema)  # create the tables once
    token = uuid4()
    results: list[bool] = []
    barrier = threading.Barrier(8)

    def worker() -> None:
        s = PostgresStateStore(connect(PG_DSN or ""), schema)
        barrier.wait()
        results.append(s.consume_token(token))

    threads = [threading.Thread(target=worker) for _ in range(8)]
    for t in threads:
        t.start()
    for t in threads:
        t.join()
    assert sorted(results) == [False] * 7 + [True]


@needs_pg
def test_postgres_state_survives_a_restart(pg_schema: tuple[Any, str]) -> None:
    from aitrader.state.postgres import PostgresOrderBook, PostgresStateStore, connect

    conn, schema = pg_schema
    first = PostgresStateStore(conn, schema)
    token, alert = uuid4(), uuid4()
    first.consume_token(token)
    first.set_frozen(alert, NOW)
    PostgresOrderBook(conn, schema).add_fill(_fill("T9", Side.BUY, 5))
    reopened = PostgresStateStore(connect(PG_DSN or ""), schema)  # a new process
    assert not reopened.consume_token(token)
    assert reopened.freeze_status().reason_alert_ids == (alert,)
    assert [f.broker_trade_id for f in PostgresOrderBook(connect(PG_DSN or ""), schema).fills()] == ["T9"]


@needs_pg
def test_execution_runs_on_postgres(pg_schema: tuple[Any, str]) -> None:
    from aitrader.state.postgres import PostgresOrderBook, PostgresStateStore

    conn, schema = pg_schema
    r = rig(state=PostgresStateStore(conn, schema), book=PostgresOrderBook(conn, schema))
    instruction = r.instruction(0)
    r.submit(instruction)
    assert r.submit(instruction) is None
    r.session(1)
    assert r.position() == 100 and r.live_protection() == 100
    assert run(r.agent.reconcile(r.at(1, 15, 40))).matched


# ---------------------------------------------------------------------- Redis Streams


def _heartbeat(agent: AgentId = AgentId.CHART) -> Envelope:
    return Envelope(
        topic=Topic.SYSTEM_HEARTBEAT,
        agent_id=AgentId.ORCHESTRATOR,
        event_time=NOW,
        available_at=NOW,
        produced_at=NOW,
        payload=AgentHeartbeat(agent_id=agent, status=HealthStatus.HEALTHY),
    )


def _alert() -> Envelope:
    return Envelope(
        topic=Topic.RISK_ALERTS,
        agent_id=AgentId.HUMAN,
        event_time=NOW,
        available_at=NOW,
        produced_at=NOW,
        priority=Priority.INTERRUPT,
        payload=RiskAlert(
            alert_id=uuid4(),
            alert_type=AlertType.KILL_SWITCH,
            severity=Severity.CRITICAL,
            freeze_new_orders=True,
            detail="stop",
        ),
    )


async def _redis_bus(prefix: str) -> Any:
    from aitrader.bus.redis_streams import RedisStreamsBus

    return RedisStreamsBus.from_url(REDIS_URL or "", InMemoryAuditLog(lambda: NOW), prefix=prefix)


async def _cleanup(bus: Any, prefix: str) -> None:
    keys = [k async for k in bus._redis.scan_iter(f"{prefix}:*")]
    if keys:
        await bus._redis.delete(*keys)
    await bus._redis.aclose()


@needs_redis
def test_redis_bus_delivers_once_per_group_interrupts_first() -> None:
    async def scenario() -> None:
        prefix = f"t{uuid4().hex[:10]}"
        bus = await _redis_bus(prefix)
        seen: list[Topic] = []
        other: list[Topic] = []

        async def handler(env: Envelope) -> None:
            seen.append(env.topic)

        async def other_handler(env: Envelope) -> None:
            other.append(env.topic)

        bus.subscribe([Topic.SYSTEM_HEARTBEAT, Topic.RISK_ALERTS], handler, group="a")
        bus.subscribe([Topic.SYSTEM_HEARTBEAT], other_handler, group="b")
        beat = _heartbeat()
        await bus.publish(beat)
        await bus.publish(beat)  # at-least-once publishing: a duplicate is harmless
        await bus.publish(_alert())
        await bus.drain()
        assert seen == [Topic.RISK_ALERTS, Topic.SYSTEM_HEARTBEAT]
        assert other == [Topic.SYSTEM_HEARTBEAT]
        replayed = await bus.replay([Topic.SYSTEM_HEARTBEAT, Topic.RISK_ALERTS])
        assert len(replayed) == 3 and bus._audit.verify_chain()
        await _cleanup(bus, prefix)

    run(scenario())


@needs_redis
def test_redis_bus_redelivers_failures_then_dead_letters() -> None:
    async def scenario() -> None:
        prefix = f"t{uuid4().hex[:10]}"
        bus = await _redis_bus(prefix)
        calls = {"flaky": 0, "poison": 0}

        async def flaky(env: Envelope) -> None:
            assert isinstance(env.payload, AgentHeartbeat)
            key = "flaky" if env.payload.agent_id is AgentId.CHART else "poison"
            calls[key] += 1
            if key == "poison" or calls[key] == 1:
                raise RuntimeError("handler crashed")

        bus.subscribe([Topic.SYSTEM_HEARTBEAT], flaky, group="g")
        await bus.publish(_heartbeat(AgentId.CHART))
        await bus.publish(_heartbeat(AgentId.TREND))
        for _ in range(5):
            await bus.poll()
        assert calls == {"flaky": 2, "poison": 3}
        assert len(bus.dead_lettered) == 1
        assert await bus._redis.xlen(f"{prefix}:dead:g") == 1
        await _cleanup(bus, prefix)

    run(scenario())


@needs_redis
def test_redis_bus_rejects_invalid_envelopes_before_writing() -> None:
    async def scenario() -> None:
        prefix = f"t{uuid4().hex[:10]}"
        bus = await _redis_bus(prefix)
        bad = Envelope.model_construct(**(_heartbeat().__dict__ | {"agent_id": AgentId.CHART}))
        with pytest.raises(ValueError):
            await bus.publish(bad)
        assert await bus._redis.exists(f"{prefix}:topic:system.heartbeat") == 0
        await _cleanup(bus, prefix)

    run(scenario())


@needs_pg
@needs_redis
def test_paper_trading_on_the_durable_stack_matches_in_memory(pg_schema: tuple[Any, str]) -> None:
    """Same market, same window: Redis Streams + Postgres must reproduce the in-memory fills."""
    from datetime import timedelta

    from aitrader.agents.data.agent import MarketData
    from aitrader.agents.risk.agent import RiskAgent
    from aitrader.bus.redis_streams import RedisStreamsBus
    from aitrader.config import load_risk_limits
    from aitrader.state.postgres import PostgresOrderBook, PostgresStateStore
    from aitrader.storage import CachedPitReader
    from tests.chain_helpers import KEY, market_store
    from tests.test_paper_trading import build, fingerprint_of

    conn, schema = pg_schema
    store, days = market_store()
    window = days[-15:]
    memory = build(store, days)
    run(memory.run(window))
    assert fingerprint_of(memory), "the window must contain trades for the comparison to mean anything"

    async def durable() -> list[tuple[object, ...]]:
        from aitrader.agents.risk.tokens import TokenSigner

        prefix = f"t{uuid4().hex[:10]}"
        audit = InMemoryAuditLog(lambda: NOW)
        bus = RedisStreamsBus.from_url(REDIS_URL or "", audit, prefix=prefix)
        state = PostgresStateStore(conn, schema)
        limits = load_risk_limits()
        signer = TokenSigner(KEY, ttl=timedelta(seconds=limits.execution.approval_token_ttl_seconds))
        risk = RiskAgent(MarketData(CachedPitReader(store)), limits, state, signer, min_analysts=2)
        trader = build(
            store, days, bus=bus, state=state, book=PostgresOrderBook(conn, schema), risk=risk, audit=audit
        )
        await trader.run(window)
        await _cleanup(bus, prefix)
        return fingerprint_of(trader)

    assert run(durable()) == fingerprint_of(memory)
