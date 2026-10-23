"""Execution-layer payloads: order instructions, the order state machine and fills."""

from __future__ import annotations

from enum import StrEnum
from typing import Annotated, Literal
from uuid import UUID

from pydantic import Field, model_validator

from aitrader.schemas.base import Instrument, InstrumentType, Price, StrictModel, UtcDatetime
from aitrader.schemas.canonical import digest
from aitrader.schemas.decision import Action
from aitrader.schemas.gates import ExecAlgo
from aitrader.schemas.risk import ApprovalToken


class Side(StrEnum):
    BUY = "BUY"
    SELL = "SELL"


class OrderType(StrEnum):
    LIMIT = "LIMIT"
    MARKET = "MARKET"  # disabled by default in config; marketable limits are preferred
    SL = "SL"
    SL_M = "SL-M"


class Product(StrEnum):
    CNC = "CNC"  # equity delivery
    MIS = "MIS"  # intraday
    NRML = "NRML"  # F&O carry-forward


class InstructionIntent(StrEnum):
    OPEN = "open"  # open or add
    CLOSE = "close"  # exit or reduce
    HEDGE = "hedge"


class UnsignedInstruction(StrictModel):
    """Everything the risk approval token is bound to."""

    instruction_id: UUID
    idempotency_key: Annotated[str, Field(min_length=16, max_length=128)]
    decision_id: UUID
    action: Action
    intent: InstructionIntent
    instrument: Instrument
    side: Side
    quantity: Annotated[int, Field(gt=0)]
    order_type: OrderType
    limit_price: Price | None = None
    trigger_price: Price | None = None
    product: Product
    open_short: bool = False
    algo: ExecAlgo
    participation_rate: Annotated[float, Field(gt=0.0, le=0.25)]
    horizon_minutes: Annotated[int, Field(ge=1)]
    protective_stop: Price | None = None
    targets: tuple[Price, ...] = ()
    max_price_deviation_bps: Annotated[float, Field(gt=0, le=500)]
    valid_until: UtcDatetime

    @model_validator(mode="after")
    def _semantics(self) -> UnsignedInstruction:
        if self.action in (Action.HOLD, Action.WAIT):
            raise ValueError("HOLD and WAIT never produce order instructions")
        if self.action is Action.BUY and (self.side, self.intent) != (Side.BUY, InstructionIntent.OPEN):
            raise ValueError("BUY maps to opening or adding a long")
        if self.action is Action.SELL and self.side is not Side.SELL:
            raise ValueError("SELL maps to a sell order")
        if self.action is Action.REDUCE and self.intent is not InstructionIntent.CLOSE:
            raise ValueError("REDUCE only closes or reduces existing positions")
        if self.action is Action.HEDGE and self.intent is not InstructionIntent.HEDGE:
            raise ValueError("HEDGE instructions carry the hedge intent")
        if self.open_short and not (self.action is Action.SELL and self.intent is InstructionIntent.OPEN):
            raise ValueError("open_short requires an explicit SELL open instruction")
        if self.side is Side.SELL and self.intent is InstructionIntent.OPEN and not self.open_short:
            raise ValueError("a sell that opens a position must set open_short explicitly")
        if self.intent is InstructionIntent.OPEN and self.protective_stop is None:
            raise ValueError("opening instructions need a protective stop")
        if self.order_type in (OrderType.LIMIT, OrderType.SL) and self.limit_price is None:
            raise ValueError("limit orders need a limit price")
        if self.order_type in (OrderType.SL, OrderType.SL_M) and self.trigger_price is None:
            raise ValueError("stop orders need a trigger price")
        derivative = self.instrument.instrument_type in (InstrumentType.FUTURE, InstrumentType.OPTION)
        if derivative and self.quantity % self.instrument.lot_size:
            raise ValueError("derivative quantity must be a multiple of the lot size")
        if derivative and self.product is Product.CNC:
            raise ValueError("derivatives cannot use the CNC product")
        return self

    def digest(self) -> str:
        return digest(self.model_dump(mode="json", exclude={"kind", "approval"}))


class OrderInstruction(UnsignedInstruction):
    kind: Literal["order_instruction"] = "order_instruction"
    approval: ApprovalToken

    @model_validator(mode="after")
    def _token_binds_instruction(self) -> OrderInstruction:
        if self.approval.decision_id != self.decision_id:
            raise ValueError("approval token belongs to a different decision")
        if self.approval.instruction_digest != self.digest():
            raise ValueError("approval token does not cover this exact instruction")
        return self


class OrderState(StrEnum):
    PENDING = "pending"
    PLACED = "placed"
    PARTIALLY_FILLED = "partially_filled"
    FILLED = "filled"
    CANCELLED = "cancelled"
    REJECTED = "rejected"


TERMINAL_STATES = frozenset({OrderState.FILLED, OrderState.CANCELLED, OrderState.REJECTED})

ALLOWED_TRANSITIONS: dict[OrderState, frozenset[OrderState]] = {
    OrderState.PENDING: frozenset({OrderState.PLACED, OrderState.REJECTED, OrderState.CANCELLED}),
    OrderState.PLACED: frozenset(
        {OrderState.PARTIALLY_FILLED, OrderState.FILLED, OrderState.CANCELLED, OrderState.REJECTED}
    ),
    OrderState.PARTIALLY_FILLED: frozenset(
        {OrderState.PARTIALLY_FILLED, OrderState.FILLED, OrderState.CANCELLED}
    ),
    OrderState.FILLED: frozenset(),
    OrderState.CANCELLED: frozenset(),
    OrderState.REJECTED: frozenset(),
}


def can_transition(current: OrderState, new: OrderState) -> bool:
    return new in ALLOWED_TRANSITIONS[current]


class OrderUpdate(StrictModel):
    kind: Literal["order_update"] = "order_update"
    instruction_id: UUID
    child_order_id: Annotated[str, Field(min_length=1, max_length=64)]
    broker_order_id: str | None = None
    state: OrderState
    previous_state: OrderState | None = None
    filled_quantity: Annotated[int, Field(ge=0)]
    reason: str | None = None

    @model_validator(mode="after")
    def _legal_transition(self) -> OrderUpdate:
        if self.previous_state is not None and not can_transition(self.previous_state, self.state):
            raise ValueError(f"illegal transition {self.previous_state} -> {self.state}")
        return self


class Fill(StrictModel):
    kind: Literal["fill"] = "fill"
    instruction_id: UUID
    child_order_id: Annotated[str, Field(min_length=1, max_length=64)]
    broker_trade_id: Annotated[str, Field(min_length=1, max_length=64)]  # dedupe key
    instrument: Instrument
    side: Side
    quantity: Annotated[int, Field(gt=0)]
    price: Price
    filled_at: UtcDatetime
    arrival_price: Price
    slippage_bps: float
