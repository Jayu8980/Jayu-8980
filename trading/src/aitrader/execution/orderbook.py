"""The execution agent's durable order book and fill ledger.

It records every accepted instruction (with its approval token), every child order and its
state, every protective order and every fill. Child-order updates must follow the order
state machine; fills are keyed by the broker trade id, so a trade seen twice is booked once.
The ledger's net positions are what reconciliation compares with the broker.

``InMemoryOrderBook`` is for tests and backtests; ``PostgresOrderBook`` (state.postgres)
keeps the same contract across processes and restarts.
"""

from __future__ import annotations

import threading
from collections import defaultdict
from decimal import Decimal
from enum import StrEnum
from typing import Annotated, Literal, Protocol
from uuid import UUID

from pydantic import Field

from aitrader.schemas.base import Instrument, StrictModel, UtcDatetime
from aitrader.schemas.orders import (
    TERMINAL_STATES,
    Fill,
    OrderInstruction,
    OrderState,
    Product,
    Side,
    can_transition,
)


class IllegalTransitionError(ValueError):
    pass


class InstructionStatus(StrEnum):
    ACCEPTED = "accepted"  # token verified, checks passed, children created
    REJECTED = "rejected"  # token verified but a pre-trade check failed


class InstructionRecord(StrictModel):
    instruction: OrderInstruction
    status: InstructionStatus
    received_at: UtcDatetime
    arrival_price: Annotated[float, Field(gt=0)] | None = None  # last price when received
    reason: str | None = None


class ChildKind(StrEnum):
    ENTRY = "entry"  # opens or adds
    EXIT = "exit"  # closes or reduces


class ChildOrder(StrictModel):
    child_order_id: Annotated[str, Field(pattern=r"^[A-Za-z0-9]{1,20}$")]  # also the broker tag
    instruction_id: UUID
    kind: ChildKind
    slice_index: Annotated[int, Field(ge=0)]
    instrument: Instrument
    side: Side
    quantity: Annotated[int, Field(gt=0)]
    limit_price: Decimal
    product: Product
    not_before: UtcDatetime  # scheduled release time for sliced orders
    state: OrderState = OrderState.PENDING
    broker_order_id: str | None = None
    filled_quantity: Annotated[int, Field(ge=0)] = 0
    average_price: float | None = None
    attempts: Annotated[int, Field(ge=0)] = 0
    outcome_unknown: bool = False  # a placement attempt failed in transit
    reason: str | None = None
    updated_at: UtcDatetime

    @property
    def open(self) -> bool:
        return self.state not in TERMINAL_STATES


class Protection(StrictModel):
    """A broker-held stop (and optional target) covering part of a position."""

    protection_id: Annotated[str, Field(pattern=r"^[A-Za-z0-9]{1,20}$")]
    instruction_id: UUID  # the entry instruction whose signed levels it uses
    instrument: Instrument
    side: Side  # exit side
    quantity: Annotated[int, Field(gt=0)]
    stop_trigger: Decimal
    stop_limit: Decimal
    target: Decimal | None = None
    product: Product
    broker_protective_id: str | None = None
    status: Literal["pending", "active", "triggered", "cancelled"] = "pending"
    triggered_order_id: str | None = None
    updated_at: UtcDatetime

    @property
    def live(self) -> bool:
        return self.status in ("pending", "active")


class OrderBook(Protocol):
    def add_instruction(self, record: InstructionRecord) -> bool:
        """False if the instruction id is already recorded."""
        ...

    def instruction(self, instruction_id: UUID) -> InstructionRecord | None: ...

    def instructions(self) -> list[InstructionRecord]: ...

    def add_child(self, child: ChildOrder) -> None: ...

    def update_child(self, child: ChildOrder) -> None:
        """Replace a child; raises IllegalTransitionError unless the state change is legal."""
        ...

    def child(self, child_order_id: str) -> ChildOrder | None: ...

    def children(self, instruction_id: UUID | None = None) -> list[ChildOrder]: ...

    def put_protection(self, protection: Protection) -> None: ...

    def protections(self) -> list[Protection]: ...

    def add_fill(self, fill: Fill) -> bool:
        """False if the broker trade id was already booked."""
        ...

    def fills(self) -> list[Fill]: ...


def check_update(old: ChildOrder, new: ChildOrder) -> None:
    if old.state is new.state:
        if new.filled_quantity < old.filled_quantity:
            raise IllegalTransitionError("filled quantity cannot decrease")
        if old.state in TERMINAL_STATES and new != old:
            raise IllegalTransitionError(f"{old.child_order_id} is already {old.state}")
        return
    if not can_transition(old.state, new.state):
        raise IllegalTransitionError(f"illegal transition {old.state} -> {new.state}")
    if new.filled_quantity < old.filled_quantity:
        raise IllegalTransitionError("filled quantity cannot decrease")


def net_positions(fills: list[Fill]) -> dict[str, int]:
    out: dict[str, int] = defaultdict(int)
    for f in fills:
        out[f.instrument.key] += f.quantity if f.side is Side.BUY else -f.quantity
    return {k: v for k, v in out.items() if v}


class InMemoryOrderBook:
    def __init__(self) -> None:
        self._lock = threading.Lock()
        self._instructions: dict[UUID, InstructionRecord] = {}
        self._children: dict[str, ChildOrder] = {}
        self._protections: dict[str, Protection] = {}
        self._fills: dict[str, Fill] = {}

    def add_instruction(self, record: InstructionRecord) -> bool:
        with self._lock:
            key = record.instruction.instruction_id
            if key in self._instructions:
                return False
            self._instructions[key] = record
            return True

    def instruction(self, instruction_id: UUID) -> InstructionRecord | None:
        return self._instructions.get(instruction_id)

    def instructions(self) -> list[InstructionRecord]:
        return list(self._instructions.values())

    def add_child(self, child: ChildOrder) -> None:
        with self._lock:
            if child.child_order_id in self._children:
                raise ValueError(f"child {child.child_order_id} already exists")
            self._children[child.child_order_id] = child

    def update_child(self, child: ChildOrder) -> None:
        with self._lock:
            check_update(self._children[child.child_order_id], child)
            self._children[child.child_order_id] = child

    def child(self, child_order_id: str) -> ChildOrder | None:
        return self._children.get(child_order_id)

    def children(self, instruction_id: UUID | None = None) -> list[ChildOrder]:
        return [c for c in self._children.values() if instruction_id in (None, c.instruction_id)]

    def put_protection(self, protection: Protection) -> None:
        with self._lock:
            self._protections[protection.protection_id] = protection

    def protections(self) -> list[Protection]:
        return list(self._protections.values())

    def add_fill(self, fill: Fill) -> bool:
        with self._lock:
            if fill.broker_trade_id in self._fills:
                return False
            self._fills[fill.broker_trade_id] = fill
            return True

    def fills(self) -> list[Fill]:
        return list(self._fills.values())
