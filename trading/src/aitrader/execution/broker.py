"""Broker contract shared by the paper broker and the Kite Connect adapter.

The execution agent is the only caller. Two error types matter:
- ``OrderRejectedError``: the broker definitively refused the request. Nothing is live.
- ``BrokerUnavailableError``: transport failure or timeout. The outcome is unknown (the order may
  be live), so callers must look the order up by its tag before placing it again.

Protective orders are broker-held one-cancels-other pairs (a stop leg and an optional target
leg) that survive overnight; Kite implements them as GTT triggers.
"""

from __future__ import annotations

from collections.abc import Callable
from datetime import datetime
from decimal import Decimal
from enum import StrEnum
from typing import Annotated, Any, Protocol

from pydantic import Field, model_validator

from aitrader.audit.interface import AuditLog
from aitrader.schemas.audit import BrokerCallRecord
from aitrader.schemas.base import Instrument, Price, StrictModel, UtcDatetime
from aitrader.schemas.canonical import canonical_json
from aitrader.schemas.orders import OrderType, Product, Side

# Kite accepts alphanumeric tags of at most 20 characters; every broker uses the same rule.
Tag = Annotated[str, Field(pattern=r"^[A-Za-z0-9]{1,20}$")]


class BrokerError(RuntimeError):
    pass


class OrderRejectedError(BrokerError):
    """Definitive refusal: nothing was placed."""


class BrokerUnavailableError(BrokerError):
    """Transport failure or timeout: the request may or may not have taken effect."""


class BrokerOrderStatus(StrEnum):
    OPEN = "open"  # resting, possibly partially filled
    TRIGGER_PENDING = "trigger_pending"
    COMPLETE = "complete"
    CANCELLED = "cancelled"
    REJECTED = "rejected"


class OrderRequest(StrictModel):
    tag: Tag
    instrument: Instrument
    side: Side
    quantity: Annotated[int, Field(gt=0)]
    order_type: OrderType
    price: Price | None = None
    trigger_price: Price | None = None
    product: Product
    after_market: bool = False  # queued for the next session (Kite "amo" variety)

    @model_validator(mode="after")
    def _prices(self) -> OrderRequest:
        if self.order_type in (OrderType.LIMIT, OrderType.SL) and self.price is None:
            raise ValueError("limit orders need a price")
        if self.order_type in (OrderType.SL, OrderType.SL_M) and self.trigger_price is None:
            raise ValueError("stop orders need a trigger price")
        return self


class BrokerOrder(StrictModel):
    broker_order_id: Annotated[str, Field(min_length=1, max_length=64)]
    tag: str | None = None
    instrument: Instrument
    side: Side
    quantity: Annotated[int, Field(gt=0)]
    filled_quantity: Annotated[int, Field(ge=0)]
    average_price: float | None = None
    status: BrokerOrderStatus
    status_message: str | None = None
    order_type: OrderType
    price: Decimal | None = None
    placed_at: UtcDatetime | None = None


class BrokerTrade(StrictModel):
    trade_id: Annotated[str, Field(min_length=1, max_length=64)]
    broker_order_id: Annotated[str, Field(min_length=1, max_length=64)]
    tag: str | None = None
    instrument: Instrument
    side: Side
    quantity: Annotated[int, Field(gt=0)]
    price: Annotated[float, Field(gt=0)]
    filled_at: UtcDatetime


class BrokerPosition(StrictModel):
    instrument: Instrument
    quantity: int  # net, signed; settled holdings plus today's net for cash equities
    average_price: float


class Quote(StrictModel):
    instrument: Instrument
    last_price: Annotated[float, Field(gt=0)]
    lower_circuit: Annotated[float, Field(gt=0)]
    upper_circuit: Annotated[float, Field(gt=0)]
    as_of: UtcDatetime


class ProtectiveRequest(StrictModel):
    tag: Tag
    instrument: Instrument
    side: Side  # the exit side
    quantity: Annotated[int, Field(gt=0)]
    stop_trigger: Price
    stop_limit: Price  # broker-held stops are limit orders once triggered (Kite GTT)
    target: Price | None = None
    product: Product

    @model_validator(mode="after")
    def _levels(self) -> ProtectiveRequest:
        sell = self.side is Side.SELL
        if (self.stop_limit > self.stop_trigger) if sell else (self.stop_limit < self.stop_trigger):
            raise ValueError("the stop limit must allow slippage beyond the trigger")
        if self.target is not None and (
            (self.target <= self.stop_trigger) if sell else (self.target >= self.stop_trigger)
        ):
            raise ValueError("target and stop are on the wrong sides")
        return self


class ProtectiveStatus(StrEnum):
    ACTIVE = "active"
    TRIGGERED = "triggered"
    CANCELLED = "cancelled"


class ProtectiveOrder(StrictModel):
    protective_id: Annotated[str, Field(min_length=1, max_length=64)]
    tag: str | None = None  # Kite GTTs carry no tag; they are matched by their levels
    instrument: Instrument
    side: Side
    quantity: Annotated[int, Field(gt=0)]
    stop_trigger: Decimal
    target: Decimal | None = None
    status: ProtectiveStatus
    triggered_order_id: str | None = None  # the regular order the trigger placed


class Broker(Protocol):
    name: str

    def place_order(self, request: OrderRequest) -> str: ...

    def cancel_order(self, broker_order_id: str) -> None: ...

    def orders(self) -> list[BrokerOrder]: ...

    def trades(self) -> list[BrokerTrade]: ...

    def positions(self) -> list[BrokerPosition]: ...

    def available_margin(self) -> float: ...

    def quote(self, instrument: Instrument) -> Quote: ...

    def place_protective(self, request: ProtectiveRequest) -> str: ...

    def cancel_protective(self, protective_id: str) -> None: ...

    def protective_orders(self) -> list[ProtectiveOrder]: ...


def _dump(value: Any) -> Any:
    if isinstance(value, StrictModel):
        return value.model_dump(mode="json")
    if isinstance(value, list):
        return [_dump(v) for v in value]
    return value


class AuditedBroker:
    """Records every broker call (request, response or error) in the audit log."""

    def __init__(self, inner: Broker, audit: AuditLog, clock: Callable[[], datetime]) -> None:
        self._inner = inner
        self._audit = audit
        self._clock = clock
        self.name = inner.name

    def _call(self, method: str, request: dict[str, Any], fn: Callable[[], Any]) -> Any:
        started = self._clock()
        try:
            result = fn()
        except Exception as exc:
            self._record(method, request, None, f"{type(exc).__name__}: {exc}", started)
            raise
        self._record(method, request, _dump(result), None, started)
        return result

    def _record(
        self, method: str, request: dict[str, Any], response: Any, error: str | None, started: datetime
    ) -> None:
        record = BrokerCallRecord(
            broker=self.name,
            method=method,
            request=request,
            response=response,
            error=error,
            started_at=started,
            finished_at=max(self._clock(), started),
        )
        self._audit.append("broker_call", canonical_json(record))

    def place_order(self, request: OrderRequest) -> str:
        result: str = self._call("place_order", _dump(request), lambda: self._inner.place_order(request))
        return result

    def cancel_order(self, broker_order_id: str) -> None:
        self._call(
            "cancel_order",
            {"broker_order_id": broker_order_id},
            lambda: self._inner.cancel_order(broker_order_id),
        )

    def orders(self) -> list[BrokerOrder]:
        result: list[BrokerOrder] = self._call("orders", {}, self._inner.orders)
        return result

    def trades(self) -> list[BrokerTrade]:
        result: list[BrokerTrade] = self._call("trades", {}, self._inner.trades)
        return result

    def positions(self) -> list[BrokerPosition]:
        result: list[BrokerPosition] = self._call("positions", {}, self._inner.positions)
        return result

    def available_margin(self) -> float:
        result: float = self._call("available_margin", {}, self._inner.available_margin)
        return result

    def quote(self, instrument: Instrument) -> Quote:
        result: Quote = self._call(
            "quote", {"instrument": instrument.key}, lambda: self._inner.quote(instrument)
        )
        return result

    def place_protective(self, request: ProtectiveRequest) -> str:
        result: str = self._call(
            "place_protective", _dump(request), lambda: self._inner.place_protective(request)
        )
        return result

    def cancel_protective(self, protective_id: str) -> None:
        self._call(
            "cancel_protective",
            {"protective_id": protective_id},
            lambda: self._inner.cancel_protective(protective_id),
        )

    def protective_orders(self) -> list[ProtectiveOrder]:
        result: list[ProtectiveOrder] = self._call("protective_orders", {}, self._inner.protective_orders)
        return result
