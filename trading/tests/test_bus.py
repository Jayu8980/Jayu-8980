import asyncio
from datetime import timedelta
from uuid import uuid4

import pytest
from pydantic import ValidationError

from aitrader.audit import InMemoryAuditLog
from aitrader.bus import InMemoryBus
from aitrader.schemas.base import AgentId, Priority
from aitrader.schemas.envelope import Envelope, Topic
from tests.factories import T0, envelope
from tests.test_audit import Clock


def _bus() -> tuple[InMemoryBus, InMemoryAuditLog]:
    audit = InMemoryAuditLog(Clock())
    return InMemoryBus(audit), audit


def test_delivers_and_audits_before_delivery() -> None:
    bus, audit = _bus()
    seen: list[int] = []

    async def handler(env: Envelope) -> None:
        seen.append(len(audit.records()))

    bus.subscribe([Topic.DATA_BARS], handler, group="g")
    asyncio.run(bus.publish(envelope(Topic.DATA_BARS)))
    assert seen == [1]  # the record existed when the handler ran


def test_interrupts_jump_the_queue() -> None:
    bus, _ = _bus()
    order: list[Topic] = []

    async def first(env: Envelope) -> None:
        order.append(env.topic)
        if env.topic is Topic.DATA_BARS and len(order) == 1:
            await bus.publish(envelope(Topic.SIGNALS_CHART))
            await bus.publish(envelope(Topic.RISK_ALERTS))

    bus.subscribe(list(Topic), first, group="all")
    asyncio.run(bus.publish(envelope(Topic.DATA_BARS)))
    assert order == [Topic.DATA_BARS, Topic.RISK_ALERTS, Topic.SIGNALS_CHART]


def test_duplicate_messages_are_delivered_once_per_group() -> None:
    bus, audit = _bus()
    counts = {"a": 0, "b": 0}

    async def a(env: Envelope) -> None:
        counts["a"] += 1

    async def b(env: Envelope) -> None:
        counts["b"] += 1

    bus.subscribe([Topic.DATA_BARS], a, group="a")
    bus.subscribe([Topic.DATA_BARS], b, group="b")
    env = envelope(Topic.DATA_BARS)

    async def twice() -> None:
        await bus.publish(env)
        await bus.publish(env)

    asyncio.run(twice())
    assert counts == {"a": 1, "b": 1}
    assert len(audit.records()) == 2  # both publish attempts are on the record


def test_unvalidated_envelopes_are_refused() -> None:
    bus, audit = _bus()
    forged = Envelope.model_construct(
        message_id=uuid4(),
        schema_version="1.0.0",
        topic=Topic.ORDERS_INSTRUCTIONS,
        agent_id=AgentId.BOSS,  # not authorised
        cycle_id=None,
        causation_ids=(),
        event_time=T0,
        available_at=T0,
        produced_at=T0 + timedelta(seconds=1),
        priority=Priority.NORMAL,
        payload=envelope(Topic.ORDERS_INSTRUCTIONS).payload,
    )
    with pytest.raises(ValidationError, match="may not publish"):
        asyncio.run(bus.publish(forged))
    assert audit.records() == []


def test_replay_returns_audited_envelopes_by_topic() -> None:
    bus, _ = _bus()

    async def run() -> list[Envelope]:
        await bus.publish(envelope(Topic.DATA_BARS))
        await bus.publish(envelope(Topic.SIGNALS_CHART))
        return await bus.replay([Topic.SIGNALS_CHART])

    replayed = asyncio.run(run())
    assert [e.topic for e in replayed] == [Topic.SIGNALS_CHART]
