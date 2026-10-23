"""Paper broker: a simulated exchange and broker behind the same ``Broker`` contract as Kite.

It is driven by daily bars in two steps per session, because daily data cannot say what
happened in between:
- ``open_session(day)``: resting orders (placed after the previous close) trade at the open
  if marketable; broker-held stops and targets gapped through at the open trigger there.
- ``run_session(day)``: resting limits touched during the day fill at their limit; stops and
  targets touched intraday trigger (stop first when the bar touches both, as in the
  backtester); unfilled day orders expire at the close.
The execution agent runs between the two, so stops for positions opened at the open are in
place before the rest of the session is simulated.

Fills pay slippage (half spread plus square-root impact on the day's volume and range) and
dated Indian costs, never better than the order's limit. Each session caps the quantity
traded per instrument at ``max_participation`` of the day's volume. Like a real broker it
refuses limits outside the price band, sells of cash equity it does not hold and orders it
has no margin for; triggered stops are limit orders and can stay unfilled in a gap.

Faults can be injected per method ("down": fail before acting; "lost_ack": act, then fail)
to test that the execution agent never duplicates an order when an acknowledgement is lost.
"""

from __future__ import annotations

import math
from collections import defaultdict
from collections.abc import Callable
from dataclasses import dataclass, field
from datetime import date, datetime, timedelta
from decimal import Decimal
from typing import Literal

from aitrader.agents.data.agent import MarketData
from aitrader.agents.data.calendar import SESSION_CLOSE, SESSION_OPEN, ist_datetime
from aitrader.execution.broker import (
    BrokerOrder,
    BrokerOrderStatus,
    BrokerPosition,
    BrokerTrade,
    BrokerUnavailableError,
    OrderRejectedError,
    OrderRequest,
    ProtectiveOrder,
    ProtectiveRequest,
    ProtectiveStatus,
    Quote,
)
from aitrader.schemas.base import Instrument, InstrumentType, Timeframe
from aitrader.schemas.market import Bar
from aitrader.schemas.orders import OrderType, Product, Side
from aitrader.tools.costs import india_costs

Fault = Literal["down", "lost_ack"]
_TICK = Decimal("0.05")


@dataclass(frozen=True)
class PaperConfig:
    price_band: float = 0.20  # daily circuit, as a fraction of the previous close
    max_participation: float = 0.10  # of the session's volume, per instrument
    half_spread_bps: float = 5.0
    impact_coefficient: float = 0.5
    fno_margin_fraction: float = 0.25


@dataclass
class _Order:
    request: OrderRequest
    broker_order_id: str
    placed_at: datetime
    status: BrokerOrderStatus = BrokerOrderStatus.OPEN
    filled: int = 0
    notional: float = 0.0
    message: str | None = None
    protective_id: str | None = None

    def view(self) -> BrokerOrder:
        r = self.request
        return BrokerOrder(
            broker_order_id=self.broker_order_id,
            tag=r.tag if self.protective_id is None else None,
            instrument=r.instrument,
            side=r.side,
            quantity=r.quantity,
            filled_quantity=self.filled,
            average_price=self.notional / self.filled if self.filled else None,
            status=self.status,
            status_message=self.message,
            order_type=r.order_type,
            price=r.price,
            placed_at=self.placed_at,
        )


@dataclass
class _Protective:
    request: ProtectiveRequest
    protective_id: str
    status: ProtectiveStatus = ProtectiveStatus.ACTIVE
    triggered_order_id: str | None = None


@dataclass
class _Position:
    instrument: Instrument
    quantity: int = 0
    average_price: float = 0.0


@dataclass
class PaperBroker:
    market: MarketData
    clock: Callable[[], datetime]
    cash: float
    config: PaperConfig = field(default_factory=PaperConfig)
    name: str = "paper"
    faults: dict[str, list[Fault]] = field(default_factory=lambda: defaultdict(list))
    costs_paid: float = 0.0

    def __post_init__(self) -> None:
        self._orders: dict[str, _Order] = {}
        self._trades: list[BrokerTrade] = []
        self._positions: dict[str, _Position] = {}
        self._protective: dict[str, _Protective] = {}
        self._ltp: dict[str, float] = {}
        self._prev_close: dict[str, float] = {}
        self._used: dict[str, int] = defaultdict(int)
        self._bars: dict[str, Bar] = {}
        self._seq = 0

    # ------------------------------------------------------------ fault injection
    def inject(self, method: str, *faults: Fault) -> None:
        self.faults[method].extend(faults)

    def _fault(self, method: str) -> Fault | None:
        queue = self.faults.get(method)
        return queue.pop(0) if queue else None

    def _guard(self, method: str) -> Fault | None:
        fault = self._fault(method)
        if fault == "down":
            raise BrokerUnavailableError(f"{method}: connection reset")
        return fault

    def _next_id(self, prefix: str) -> str:
        self._seq += 1
        return f"{prefix}{self._seq:09d}"

    # ------------------------------------------------------------ prices
    def _bar(self, instrument: Instrument, day: date) -> Bar | None:
        # The simulator is the exchange: it may read the session it is simulating.
        bars = self.market.raw_bars(
            instrument,
            Timeframe.D1,
            ist_datetime(day, SESSION_CLOSE) + timedelta(days=1),
            start=ist_datetime(day, SESSION_OPEN) - timedelta(hours=1),
        )
        return next((b for b in bars if b.bar_end == ist_datetime(day, SESSION_CLOSE)), None)

    def last_price(self, instrument: Instrument) -> float | None:
        key = instrument.key
        if key in self._ltp:
            return self._ltp[key]
        bars = self.market.raw_bars(
            instrument, Timeframe.D1, self.clock(), start=self.clock() - timedelta(days=15)
        )
        if not bars:
            return None
        self._ltp[key] = self._prev_close[key] = float(bars[-1].close)
        return self._ltp[key]

    def _band(self, instrument: Instrument) -> tuple[float, float]:
        ref = self._prev_close.get(instrument.key) or self.last_price(instrument)
        if ref is None:
            raise OrderRejectedError(f"no price for {instrument.key}")
        return ref * (1 - self.config.price_band), ref * (1 + self.config.price_band)

    # ------------------------------------------------------------ broker contract
    def quote(self, instrument: Instrument) -> Quote:
        self._guard("quote")
        last = self.last_price(instrument)
        if last is None:
            raise OrderRejectedError(f"no price for {instrument.key}")
        low, high = self._band(instrument)
        return Quote(
            instrument=instrument, last_price=last, lower_circuit=low, upper_circuit=high, as_of=self.clock()
        )

    def place_order(self, request: OrderRequest) -> str:
        fault = self._guard("place_order")
        self._validate(request)
        order = _Order(request, self._next_id("P"), self.clock())
        self._orders[order.broker_order_id] = order
        if fault == "lost_ack":
            raise BrokerUnavailableError("place_order: response lost")
        return order.broker_order_id

    def cancel_order(self, broker_order_id: str) -> None:
        self._guard("cancel_order")
        order = self._orders.get(broker_order_id)
        if order is None:
            raise OrderRejectedError(f"unknown order {broker_order_id}")
        if order.status in (BrokerOrderStatus.OPEN, BrokerOrderStatus.TRIGGER_PENDING):
            order.status = BrokerOrderStatus.CANCELLED
            order.message = "cancelled by user"

    def orders(self) -> list[BrokerOrder]:
        self._guard("orders")
        return [o.view() for o in self._orders.values()]

    def trades(self) -> list[BrokerTrade]:
        self._guard("trades")
        return list(self._trades)

    def positions(self) -> list[BrokerPosition]:
        self._guard("positions")
        return [
            BrokerPosition(instrument=p.instrument, quantity=p.quantity, average_price=p.average_price)
            for p in self._positions.values()
            if p.quantity
        ]

    def available_margin(self) -> float:
        self._guard("available_margin")
        return self._free_margin()

    def place_protective(self, request: ProtectiveRequest) -> str:
        fault = self._guard("place_protective")
        pid = self._next_id("G")
        self._protective[pid] = _Protective(request, pid)
        if fault == "lost_ack":
            raise BrokerUnavailableError("place_protective: response lost")
        return pid

    def cancel_protective(self, protective_id: str) -> None:
        self._guard("cancel_protective")
        p = self._protective.get(protective_id)
        if p is None:
            raise OrderRejectedError(f"unknown protective order {protective_id}")
        if p.status is ProtectiveStatus.ACTIVE:
            p.status = ProtectiveStatus.CANCELLED

    def protective_orders(self) -> list[ProtectiveOrder]:
        self._guard("protective_orders")
        return [
            ProtectiveOrder(
                protective_id=p.protective_id,
                tag=p.request.tag,
                instrument=p.request.instrument,
                side=p.request.side,
                quantity=p.request.quantity,
                stop_trigger=p.request.stop_trigger,
                target=p.request.target,
                status=p.status,
                triggered_order_id=p.triggered_order_id,
            )
            for p in self._protective.values()
        ]

    # ------------------------------------------------------------ validation and margin
    @staticmethod
    def _derivative(instrument: Instrument) -> bool:
        return instrument.instrument_type in (InstrumentType.FUTURE, InstrumentType.OPTION)

    def _validate(self, r: OrderRequest) -> None:
        if r.instrument.instrument_type is InstrumentType.INDEX:
            raise OrderRejectedError("indices are not tradable")
        if r.order_type is not OrderType.LIMIT or r.price is None:
            raise OrderRejectedError("the paper broker accepts limit orders only")
        if r.price % _TICK:
            raise OrderRejectedError(f"price {r.price} is not a multiple of the tick size")
        low, high = self._band(r.instrument)
        if not low <= float(r.price) <= high:
            raise OrderRejectedError(f"price {r.price} outside the band [{low:.2f}, {high:.2f}]")
        if r.quantity % r.instrument.lot_size:
            raise OrderRejectedError("quantity is not a multiple of the lot size")
        held = self._positions.get(r.instrument.key)
        if r.side is Side.SELL and r.product is Product.CNC:
            resting = sum(
                o.request.quantity - o.filled
                for o in self._orders.values()
                if o.request.instrument.key == r.instrument.key
                and o.request.side is Side.SELL
                and o.status is BrokerOrderStatus.OPEN
            )
            if (held.quantity if held else 0) - resting < r.quantity:
                raise OrderRejectedError("insufficient holdings for a delivery sell")
        if self._margin_needed(r, r.quantity) > self._free_margin():
            raise OrderRejectedError("insufficient margin")

    def _margin_needed(self, r: OrderRequest, quantity: int) -> float:
        price = float(r.price or 0)
        pos = self._positions.get(r.instrument.key)
        reducing = pos is not None and (pos.quantity > 0) == (r.side is Side.SELL) and pos.quantity != 0
        if reducing:
            return 0.0
        if r.instrument.instrument_type is InstrumentType.FUTURE or (
            r.instrument.instrument_type is InstrumentType.OPTION and r.side is Side.SELL
        ):
            return quantity * price * self.config.fno_margin_fraction
        return quantity * price if r.side is Side.BUY else 0.0

    def _free_margin(self) -> float:
        blocked = sum(
            self._margin_needed(o.request, o.request.quantity - o.filled)
            for o in self._orders.values()
            if o.status is BrokerOrderStatus.OPEN and o.protective_id is None
        )
        for p in self._positions.values():
            if p.instrument.instrument_type is InstrumentType.FUTURE and p.quantity:
                blocked += (
                    abs(p.quantity)
                    * (self._ltp.get(p.instrument.key) or p.average_price)
                    * (self.config.fno_margin_fraction)
                )
        return self.cash - blocked

    def equity(self) -> float:
        """Cash plus marked positions (futures marked as unrealised profit and loss)."""
        total = self.cash
        for p in self._positions.values():
            price = self._ltp.get(p.instrument.key) or p.average_price
            if p.instrument.instrument_type is InstrumentType.FUTURE:
                total += p.quantity * (price - p.average_price)
            else:
                total += p.quantity * price
        return total

    # ------------------------------------------------------------ session simulation
    def open_session(self, day: date) -> None:
        self._used.clear()
        self._bars = {}
        for key in {o.request.instrument.key: o for o in self._orders.values()} | {
            p.request.instrument.key: p for p in self._protective.values()
        }:
            inst = self._instrument(key)
            if inst is not None and (bar := self._bar(inst, day)) is not None:
                self._bars[key] = bar
        for p in list(self._protective.values()):
            bar = self._bars.get(p.request.instrument.key)
            if p.status is ProtectiveStatus.ACTIVE and bar is not None:
                self._check_protective(p, bar, at_open=True)
        for order in list(self._orders.values()):
            bar = self._bars.get(order.request.instrument.key)
            if order.status is BrokerOrderStatus.OPEN and bar is not None:
                self._try_fill(order, bar, at_open=True)
        for key, bar in self._bars.items():
            self._ltp[key] = float(bar.open)

    def run_session(self, day: date) -> None:
        for order in self._orders.values():
            key = order.request.instrument.key
            resting = order.status is BrokerOrderStatus.OPEN
            if (
                key not in self._bars
                and resting
                and (bar := self._bar(order.request.instrument, day)) is not None
            ):
                self._bars[key] = bar
        for p in self._protective.values():
            key = p.request.instrument.key
            active = p.status is ProtectiveStatus.ACTIVE
            if key not in self._bars and active and (bar := self._bar(p.request.instrument, day)) is not None:
                self._bars[key] = bar
        for p in list(self._protective.values()):
            bar = self._bars.get(p.request.instrument.key)
            if p.status is ProtectiveStatus.ACTIVE and bar is not None:
                self._check_protective(p, bar, at_open=False)
        for order in list(self._orders.values()):
            bar = self._bars.get(order.request.instrument.key)
            if order.status is BrokerOrderStatus.OPEN and bar is not None:
                self._try_fill(order, bar, at_open=False)
        for order in self._orders.values():
            if order.status is BrokerOrderStatus.OPEN:
                order.status = BrokerOrderStatus.CANCELLED
                order.message = "day order expired at the close"
        for key, bar in self._bars.items():
            self._ltp[key] = self._prev_close[key] = float(bar.close)
        for pos in self._positions.values():
            if pos.quantity and pos.instrument.key not in self._bars:
                closing = self._bar(pos.instrument, day)
                if closing is not None:
                    self._ltp[pos.instrument.key] = self._prev_close[pos.instrument.key] = float(
                        closing.close
                    )

    def _instrument(self, key: str) -> Instrument | None:
        for o in self._orders.values():
            if o.request.instrument.key == key:
                return o.request.instrument
        for p in self._protective.values():
            if p.request.instrument.key == key:
                return p.request.instrument
        return None

    def _check_protective(self, p: _Protective, bar: Bar, *, at_open: bool) -> None:
        r = p.request
        sell = r.side is Side.SELL
        o, h, lo = float(bar.open), float(bar.high), float(bar.low)
        stop, target = float(r.stop_trigger), float(r.target) if r.target is not None else None
        if at_open:
            stop_hit = o <= stop if sell else o >= stop
            target_hit = target is not None and (o >= target if sell else o <= target)
        else:
            stop_hit = lo <= stop if sell else h >= stop
            target_hit = target is not None and (h >= target if sell else lo <= target)
        if not (stop_hit or target_hit):
            return
        # Stop first when a bar touches both: the conservative assumption.
        price = r.stop_limit if stop_hit else r.target
        assert price is not None
        request = OrderRequest(
            tag=r.tag,
            instrument=r.instrument,
            side=r.side,
            quantity=r.quantity,
            order_type=OrderType.LIMIT,
            price=price,
            product=r.product,
        )
        order = _Order(request, self._next_id("P"), self.clock(), protective_id=p.protective_id)
        self._orders[order.broker_order_id] = order
        p.status = ProtectiveStatus.TRIGGERED
        p.triggered_order_id = order.broker_order_id
        reference = (o if at_open else stop) if stop_hit else (o if at_open else target)
        assert reference is not None
        self._try_fill(order, bar, at_open=at_open, reference=reference)

    def _try_fill(self, order: _Order, bar: Bar, *, at_open: bool, reference: float | None = None) -> None:
        r = order.request
        assert r.price is not None
        limit = float(r.price)
        buy = r.side is Side.BUY
        o, h, lo = float(bar.open), float(bar.high), float(bar.low)
        if reference is None:
            if at_open:
                if not (o <= limit if buy else o >= limit):
                    return
                reference = o
            else:
                if not (lo <= limit if buy else h >= limit):
                    return
                reference = limit
        elif not (reference <= limit if buy else reference >= limit):
            # A triggered stop limit gapped through: it rests and fills only if the day trades back to it.
            if at_open or not (lo <= limit if buy else h >= limit):
                return
            reference = limit
        key = r.instrument.key
        lot = r.instrument.lot_size
        cap = math.floor(bar.volume * self.config.max_participation / lot) * lot - self._used[key]
        quantity = min(r.quantity - order.filled, max(cap, 0))
        if quantity <= 0:
            return
        slip = self._slippage_bps(quantity, bar)
        price = reference * (1 + slip / 1e4) if buy else reference * (1 - slip / 1e4)
        price = min(price, limit) if buy else max(price, limit)
        price = min(max(price, lo), h)
        if self._margin_needed(r, quantity) > self._free_margin() + self._margin_needed(
            r, r.quantity - order.filled
        ):
            order.status = BrokerOrderStatus.REJECTED
            order.message = "insufficient margin at execution"
            return
        self._used[key] += quantity
        self._book(order, quantity, price)

    def _slippage_bps(self, quantity: int, bar: Bar) -> float:
        o = float(bar.open)
        day_range = (float(bar.high) - float(bar.low)) / o if o else 0.0
        impact = (
            self.config.impact_coefficient * day_range * 0.5 * math.sqrt(quantity / max(bar.volume, 1)) * 1e4
        )
        return self.config.half_spread_bps + impact

    def _book(self, order: _Order, quantity: int, price: float) -> None:
        r = order.request
        inst = r.instrument
        segment = (
            "futures"
            if inst.instrument_type is InstrumentType.FUTURE
            else "options"
            if inst.instrument_type is InstrumentType.OPTION
            else "equity_intraday"
            if r.product is Product.MIS
            else "equity_delivery"
        )
        now = self.clock()
        costs = india_costs(segment, r.side.value, price, quantity, now.date()).total  # type: ignore[arg-type]
        self.costs_paid += costs
        signed = quantity if r.side is Side.BUY else -quantity
        pos = self._positions.setdefault(inst.key, _Position(inst))
        if inst.instrument_type is InstrumentType.FUTURE:
            closing = min(abs(signed), abs(pos.quantity)) if pos.quantity * signed < 0 else 0
            if closing:
                direction = 1 if pos.quantity > 0 else -1
                self.cash += closing * (price - pos.average_price) * direction
        else:
            self.cash -= signed * price
        self.cash -= costs
        new_qty = pos.quantity + signed
        if pos.quantity == 0 or (pos.quantity > 0) == (signed > 0):
            pos.average_price = (pos.average_price * abs(pos.quantity) + price * quantity) / abs(new_qty)
        elif new_qty and (new_qty > 0) != (pos.quantity > 0):
            pos.average_price = price
        pos.quantity = new_qty
        order.filled += quantity
        order.notional += quantity * price
        if order.filled == r.quantity:
            order.status = BrokerOrderStatus.COMPLETE
        self._trades.append(
            BrokerTrade(
                trade_id=self._next_id("T"),
                broker_order_id=order.broker_order_id,
                tag=r.tag if order.protective_id is None else None,
                instrument=inst,
                side=r.side,
                quantity=quantity,
                price=price,
                filled_at=now,
            )
        )
        self._ltp.setdefault(inst.key, price)
