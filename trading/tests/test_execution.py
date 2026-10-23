"""Execution agent against the paper broker: the no-bypass, no-duplicate and stay-protected
guarantees."""

from __future__ import annotations

from decimal import Decimal
from uuid import uuid4

import pytest
from pydantic import ValidationError

from aitrader.agents.risk.tokens import TokenSigner
from aitrader.execution.broker import BrokerOrderStatus, OrderRequest, ProtectiveStatus
from aitrader.execution.orderbook import ChildKind, IllegalTransitionError, InstructionStatus
from aitrader.schemas.base import AgentId, Priority
from aitrader.schemas.envelope import Envelope, Topic
from aitrader.schemas.market import Severity
from aitrader.schemas.orders import (
    Fill,
    InstructionIntent,
    OrderState,
    OrderType,
    OrderUpdate,
    Product,
    Side,
)
from aitrader.schemas.risk import AlertType, RiskAlert
from tests.execution_helpers import XYZ, rig, run


def updates(r: object) -> list[OrderUpdate]:
    return [e.payload for e in r.envelopes if isinstance(e.payload, OrderUpdate)]  # type: ignore[attr-defined]


def test_entry_fills_at_open_and_is_protected_at_signed_levels() -> None:
    r = rig()
    record = r.submit(r.instruction(0))
    assert record.status is InstructionStatus.ACCEPTED
    (child,) = r.book.children()
    assert child.state is OrderState.PLACED and child.kind is ChildKind.ENTRY
    (order,) = r.broker.orders()
    assert order.tag == child.child_order_id and order.status is BrokerOrderStatus.OPEN
    r.session(1)
    assert r.position() == 100
    (fill,) = r.book.fills()
    assert fill.quantity == 100 and float(fill.price) <= 101  # never worse than the limit
    (prot,) = r.broker.protective_orders()
    assert prot.quantity == 100 and prot.stop_trigger == Decimal("95.00") and prot.target == Decimal("110.00")
    assert r.book.child(child.child_order_id).state is OrderState.FILLED  # type: ignore[union-attr]
    report = run(r.agent.reconcile(r.at(1, 15, 40)))
    assert report.matched and not r.state.freeze_status().frozen
    states = [(u.previous_state, u.state) for u in updates(r)]
    assert (OrderState.PENDING, OrderState.PLACED) in states and (
        OrderState.PLACED,
        OrderState.FILLED,
    ) in states
    assert any(isinstance(e.payload, Fill) for e in r.envelopes)
    assert r.audit.verify_chain()
    assert any(rec.record_type == "envelope" for rec in r.audit.records())


def test_replayed_instruction_never_creates_a_second_order() -> None:
    r = rig()
    instruction = r.instruction(0)
    r.submit(instruction)
    assert r.submit(instruction) is None  # same instruction again
    env = Envelope(
        topic=Topic.ORDERS_INSTRUCTIONS,
        agent_id=AgentId.RISK,
        event_time=instruction.approval.issued_at,
        available_at=instruction.approval.issued_at,
        produced_at=instruction.approval.issued_at,
        payload=instruction,
    )
    run(r.bus.publish(env))
    run(r.bus.publish(env.model_copy(update={"message_id": uuid4()})))  # redelivered under a new id
    assert len(r.broker.orders()) == 1
    assert any("duplicate" in n for n in r.agent.notes)


def test_a_copy_with_a_new_id_but_the_same_token_is_refused() -> None:
    r = rig()
    instruction = r.instruction(0)
    r.submit(instruction)
    with pytest.raises(ValidationError):  # the token no longer covers the changed instruction
        type(instruction).model_validate(instruction.model_dump() | {"instruction_id": uuid4()})
    assert len(r.broker.orders()) == 1


def test_lost_acknowledgement_is_resolved_by_tag_lookup_not_a_resend() -> None:
    r = rig()
    r.broker.inject("place_order", "lost_ack")
    r.submit(r.instruction(0))
    assert len(r.broker.orders()) == 1
    (child,) = r.book.children()
    assert child.state is OrderState.PLACED and child.broker_order_id == r.broker.orders()[0].broker_order_id


def test_failure_before_the_broker_acts_is_retried_once_safely() -> None:
    r = rig()
    r.broker.inject("place_order", "down")
    r.submit(r.instruction(0))
    assert len(r.broker.orders()) == 1
    assert r.book.children()[0].attempts == 2


def test_unknown_outcome_freezes_instead_of_resending() -> None:
    r = rig()
    r.broker.inject("place_order", "lost_ack")
    r.broker.inject("orders", "down", "down")  # the tag lookups fail too
    r.submit(r.instruction(0))
    (child,) = r.book.children()
    assert child.outcome_unknown and child.state is OrderState.PENDING
    assert len(r.broker._orders) == 1  # live at the broker, sent exactly once
    assert r.state.freeze_status().frozen
    assert any(a.alert_type is AlertType.BROKER_DISCONNECT for a in r.agent.alerts)
    run(r.agent.sync(r.at(0, 20, 5)))  # broker reachable again: the order is adopted, not re-sent
    (child,) = r.book.children()
    assert child.state is OrderState.PLACED and not child.outcome_unknown
    assert len(r.broker._orders) == 1


def test_forged_and_foreign_tokens_are_refused_and_raise_an_alert() -> None:
    r = rig()
    forged = TokenSigner(b"x" * 40, key_id="k1")
    r.submit(r.instruction(0, signer=forged))
    assert r.broker.orders() == []
    assert r.state.freeze_status().frozen
    assert any(a.alert_type is AlertType.KILL_SWITCH for a in r.agent.alerts)
    foreign = TokenSigner(b"y" * 40, key_id="k9")
    r.submit(r.instruction(0, signer=foreign))
    assert r.broker.orders() == []
    rejected = [u for u in updates(r) if u.state is OrderState.REJECTED]
    assert len(rejected) == 2 and all("approval refused" in (u.reason or "") for u in rejected)


def test_expired_approval_is_rejected_without_a_broker_call() -> None:
    r = rig()
    instruction = r.instruction(0)
    r.submit(instruction, now=instruction.approval.expires_at)
    assert r.broker.orders() == []
    assert not r.state.freeze_status().frozen  # expiry is routine, not a security event


def test_freeze_blocks_entries_cancels_resting_ones_but_keeps_exits_and_stops() -> None:
    r = rig()
    r.submit(r.instruction(0))
    r.session(1)
    assert r.position() == 100 and r.live_protection() == 100
    r.submit(r.instruction(1, quantity=50))  # resting entry for the next open
    alert = RiskAlert(
        alert_id=uuid4(),
        alert_type=AlertType.KILL_SWITCH,
        severity=Severity.CRITICAL,
        freeze_new_orders=True,
        detail="operator kill",
    )
    now = r.at(1, 20, 30)
    run(
        r.bus.publish(
            Envelope(
                topic=Topic.RISK_ALERTS,
                agent_id=AgentId.HUMAN,
                event_time=now,
                available_at=now,
                produced_at=now,
                priority=Priority.INTERRUPT,
                payload=alert,
            )
        )
    )
    run(r.agent.sync(r.at(1, 20, 31)))
    resting = [o for o in r.broker.orders() if o.status is BrokerOrderStatus.OPEN]
    assert resting == []  # the entry was cancelled
    assert r.live_protection() == 100  # the stop stays
    refused = r.submit(r.instruction(1, quantity=20))
    assert refused.status is InstructionStatus.REJECTED and "frozen" in refused.reason
    exit_ = r.submit(
        r.instruction(1, side=Side.SELL, quantity=100, limit="99.00", intent=InstructionIntent.CLOSE)
    )
    assert exit_.status is InstructionStatus.ACCEPTED
    r.session(2)
    assert r.position() == 0


def test_exit_cancels_protection_first_so_stop_and_exit_cannot_both_sell() -> None:
    r = rig(overrides={2: (100.0, 100.5, 94.0, 96.0)})  # day 2 also trades through the stop
    r.submit(r.instruction(0))
    r.session(1)
    r.submit(r.instruction(1, side=Side.SELL, quantity=100, limit="99.00", intent=InstructionIntent.CLOSE))
    statuses = {p.status for p in r.broker.protective_orders()}
    assert statuses == {ProtectiveStatus.CANCELLED}  # cancelled before the exit was sent
    r.session(2)
    assert r.position() == 0  # sold once, never short
    assert r.live_protection() == 0
    assert run(r.agent.reconcile(r.at(2, 15, 40))).matched


def test_partial_exit_reprotects_the_remainder() -> None:
    r = rig()
    r.submit(r.instruction(0))
    r.session(1)
    r.submit(r.instruction(1, side=Side.SELL, quantity=40, limit="99.00", intent=InstructionIntent.CLOSE))
    r.session(2)
    assert r.position() == 60 and r.live_protection() == 60
    live = [p for p in r.broker.protective_orders() if p.status is ProtectiveStatus.ACTIVE]
    assert [p.quantity for p in live] == [60] and live[0].stop_trigger == Decimal("95.00")


def test_stop_and_target_execute_at_the_broker() -> None:
    stop_day = rig(overrides={2: (99.0, 99.5, 94.0, 95.5)})
    stop_day.submit(stop_day.instruction(0))
    stop_day.session(1)
    stop_day.session(2)
    assert stop_day.position() == 0
    exit_fill = stop_day.book.fills()[-1]
    assert exit_fill.side is Side.SELL and 93.0 <= float(exit_fill.price) <= 95.0
    target_day = rig(overrides={2: (101.0, 111.0, 100.5, 109.0)})
    target_day.submit(target_day.instruction(0))
    target_day.session(1)
    target_day.session(2)
    assert target_day.position() == 0
    assert float(target_day.book.fills()[-1].price) == pytest.approx(110.0)
    assert run(target_day.agent.reconcile(target_day.at(2, 15, 40))).matched


def test_gap_through_the_stop_limit_alerts_and_freezes() -> None:
    r = rig(overrides={2: (80.0, 82.0, 78.0, 81.0)})  # opens 15% below the 95 stop
    r.submit(r.instruction(0))
    r.session(1)
    r.session(2)
    assert r.position() == 100  # the triggered stop-limit never traded
    assert any(a.alert_type is AlertType.LIQUIDITY_COLLAPSE for a in r.agent.alerts)
    assert r.state.freeze_status().frozen
    assert r.live_protection() == 100  # re-armed for the next session


def test_reconciliation_flags_positions_the_ledger_does_not_know() -> None:
    r = rig()
    r.submit(r.instruction(0))
    r.at(0, 20, 10)
    r.broker.place_order(  # someone trades the account outside the system
        OrderRequest(
            tag="manual1",
            instrument=XYZ,
            side=Side.BUY,
            quantity=10,
            order_type=OrderType.LIMIT,
            price=Decimal("101"),
            product=Product.CNC,
        )
    )
    r.session(1)
    report = run(r.agent.reconcile(r.at(1, 15, 40)))
    assert not report.matched and report.position_differences == {XYZ.key: (100, 110)}
    assert r.state.freeze_status().frozen
    assert any("matches no order" in a.detail for a in r.agent.alerts)


def test_illegal_state_transitions_are_refused_by_the_order_book() -> None:
    r = rig()
    r.submit(r.instruction(0))
    r.session(1)
    child = r.book.children()[0]
    assert child.state is OrderState.FILLED
    with pytest.raises(IllegalTransitionError):
        r.book.update_child(child.model_copy(update={"state": OrderState.CANCELLED}))


def test_trades_are_booked_once_however_often_sync_runs() -> None:
    r = rig()
    r.submit(r.instruction(0))
    r.session(1)
    for minute in range(40, 45):
        run(r.agent.sync(r.at(1, 15, minute)))
    assert len(r.book.fills()) == 1 and len(r.agent.published_fills) == 1


def test_value_cap_slices_large_orders() -> None:
    r = rig()
    r.submit(r.instruction(0, quantity=4500))  # 4500 x 101 > 200,000 per order
    children = r.book.children()
    assert len(children) == 3 and sum(c.quantity for c in children) == 4500
    assert all(c.quantity * 101 <= r.limits.execution.max_order_value_inr for c in children)
    assert len({o.tag for o in r.broker.orders()}) == 3


def test_pre_trade_checks_reject_bad_instructions() -> None:
    r = rig(cash=5_000)
    fat = r.submit(r.instruction(0, limit="105.00"))
    assert fat.status is InstructionStatus.REJECTED and "deviates" in fat.reason
    poor = r.submit(r.instruction(0, quantity=100))
    assert "insufficient margin" in poor.reason
    close = r.submit(r.instruction(0, side=Side.SELL, limit="99.00", intent=InstructionIntent.CLOSE))
    assert "exceeds the position" in close.reason
    short = r.submit(r.instruction(0, side=Side.SELL, limit="99.00", open_short=True))
    assert "intraday only" in short.reason
    assert r.broker.orders() == []
