"""Order execution agent (deterministic; never an LLM).

For every instruction it receives it:
1. ignores it if already recorded (replays are harmless),
2. verifies the risk approval token (signature, digest, expiry), consuming the token and
   the idempotency key atomically; a second copy of an instruction can never pass,
3. runs the pre-trade checks against a live quote, margin and the broker's positions,
4. slices it (freeze quantity, order value cap, algo schedule) into tagged child orders,
5. places each child idempotently: when the broker's answer is lost, it looks the order up
   by its tag before trying again, and when it cannot tell, it stops and freezes trading
   rather than risk a duplicate.

``sync`` maps broker order states onto the order state machine, books each broker trade
exactly once as a ``Fill`` and keeps every open position covered by a broker-held stop (and
target) at the levels the risk agent signed. Exits cancel just enough protection first, so
an exit and a stop can never both sell the same shares. ``reconcile`` compares the fill
ledger with the broker's positions and orders; any difference freezes new entries.

Alerts (broker unreachable, reconciliation mismatch, a stop that could not be placed or did
not fill, a forged instruction) are published as priority interrupts and applied to the
state store directly, so they take effect even if the bus is down. A freeze cancels resting
entry orders; exits and protective stops are never cancelled by a freeze.
"""

from __future__ import annotations

import asyncio
import hashlib
from collections import defaultdict
from collections.abc import Awaitable, Callable
from dataclasses import dataclass, field
from datetime import datetime, timedelta
from decimal import ROUND_HALF_EVEN, Decimal
from typing import Literal
from uuid import UUID, uuid5

from aitrader.agents.publishing import publish
from aitrader.agents.risk.tokens import TokenError, TokenForgeryError, TokenReplayError, TokenSigner
from aitrader.bus.interface import MessageBus
from aitrader.config.settings import BrokerConfig, RiskLimits
from aitrader.execution.broker import (
    Broker,
    BrokerError,
    BrokerOrder,
    BrokerOrderStatus,
    BrokerTrade,
    BrokerUnavailableError,
    OrderRejectedError,
    OrderRequest,
    ProtectiveOrder,
    ProtectiveRequest,
    ProtectiveStatus,
    Quote,
)
from aitrader.execution.checks import (
    CheckInputs,
    MarketClock,
    MarketPhase,
    RateLimiter,
    opens_risk,
    pre_trade_checks,
)
from aitrader.execution.orderbook import (
    ChildKind,
    ChildOrder,
    InstructionRecord,
    InstructionStatus,
    OrderBook,
    Protection,
    net_positions,
)
from aitrader.schemas.base import AgentId, Instrument, InstrumentType, Priority, StrictModel, UtcDatetime
from aitrader.schemas.canonical import digest
from aitrader.schemas.envelope import Envelope, Topic
from aitrader.schemas.market import Severity
from aitrader.schemas.orders import Fill, OrderInstruction, OrderState, OrderType, OrderUpdate, Side
from aitrader.schemas.risk import AlertType, RiskAlert
from aitrader.state.interface import StateStore
from aitrader.tools.costs import india_costs
from aitrader.tools.microstructure import slicer

_NS = UUID("3d1f4c6e-9a2b-4e8d-b7c5-0f1e2d3c4b5a")
_TICK = Decimal("0.05")
TAG_PREFIX = "A"  # every order this system places carries a tag starting with this


def make_tag(*parts: object) -> str:
    """Deterministic 20-character alphanumeric broker tag."""
    return TAG_PREFIX + hashlib.sha256("|".join(map(str, parts)).encode()).hexdigest()[:19]


def tick(price: float) -> Decimal:
    return (Decimal(str(price)) / _TICK).to_integral_value(rounding=ROUND_HALF_EVEN) * _TICK


def _segment(instrument: Instrument) -> str:
    if instrument.instrument_type is InstrumentType.FUTURE:
        return "futures"
    if instrument.instrument_type is InstrumentType.OPTION:
        return "options"
    return "equity_delivery"


class ReconciliationReport(StrictModel):
    at: UtcDatetime
    matched: bool
    position_differences: dict[str, tuple[int, int]]  # key -> (ledger, broker)
    unknown_orders: tuple[str, ...] = ()
    error: str | None = None


@dataclass
class ExecutionAgent:
    broker: Broker
    signer: TokenSigner
    state: StateStore
    book: OrderBook
    bus: MessageBus
    limits: RiskLimits
    broker_config: BrokerConfig
    market_clock: MarketClock
    rate_limiter: RateLimiter | None = None
    max_attempts: int = 3
    failure_threshold: int = 3  # consecutive broker failures before trading freezes
    retry_delay: Callable[[int], Awaitable[None]] = field(
        default=lambda attempt: asyncio.sleep(0.5 * 2**attempt)
    )
    alerts: list[RiskAlert] = field(default_factory=list)
    notes: list[str] = field(default_factory=list)
    updates: list[OrderUpdate] = field(default_factory=list)
    published_fills: list[Fill] = field(default_factory=list)

    def __post_init__(self) -> None:
        self._failures = 0
        self._limiter = self.rate_limiter or RateLimiter(self.broker_config.max_orders_per_second)
        self._open_triggered: dict[str, int] = {}  # key -> unfilled qty of triggered stop orders
        self._alerted_triggers: set[str] = set()

    def attach(self, bus: MessageBus) -> None:
        async def on_message(envelope: Envelope) -> None:
            if isinstance(envelope.payload, OrderInstruction):
                await self.submit(envelope.payload, envelope.produced_at)
            elif isinstance(envelope.payload, RiskAlert) and envelope.agent_id is not AgentId.EXECUTION:
                # Our own alerts were applied when raised.
                await self.on_alert(envelope.payload, envelope.produced_at)

        bus.subscribe((Topic.ORDERS_INSTRUCTIONS, Topic.RISK_ALERTS), on_message, group="execution")

    # ================================================================ publishing
    async def _emit(
        self, topic: Topic, payload: StrictModel, now: datetime, priority: Priority = Priority.NORMAL
    ) -> None:
        await publish(
            self.bus,
            message_id=uuid5(_NS, f"{topic.value}|{digest(payload)}"),
            topic=topic,
            agent_id=AgentId.EXECUTION,
            payload=payload,
            available_at=now,
            produced_at=now,
            priority=priority,
        )

    async def _alert(
        self, alert_type: AlertType, detail: str, now: datetime, *, freeze: bool = True
    ) -> RiskAlert:
        alert = RiskAlert(
            alert_id=uuid5(_NS, f"{alert_type.value}|{now.isoformat()}|{detail}"),
            alert_type=alert_type,
            severity=Severity.CRITICAL,
            freeze_new_orders=freeze,
            detail=detail[:500],
        )
        if any(a.alert_id == alert.alert_id for a in self.alerts):
            return alert
        self.alerts.append(alert)
        if freeze:
            await self._freeze(alert, now)  # effective even if the bus is down
        await self._emit(Topic.RISK_ALERTS, alert, now, Priority.INTERRUPT)
        return alert

    async def _broker_failure(self, what: str, exc: Exception, now: datetime) -> None:
        self._failures += 1
        self.notes.append(f"{what}: {exc}")
        if self._failures == self.failure_threshold:
            await self._alert(
                AlertType.BROKER_DISCONNECT,
                f"{self._failures} consecutive broker failures; last: {what}: {exc}",
                now,
            )

    async def _update(self, old: ChildOrder, new: ChildOrder, now: datetime) -> ChildOrder:
        self.book.update_child(new)
        if old.state is not new.state or old.filled_quantity != new.filled_quantity:
            update = OrderUpdate(
                instruction_id=new.instruction_id,
                child_order_id=new.child_order_id,
                broker_order_id=new.broker_order_id,
                state=new.state,
                previous_state=old.state,
                filled_quantity=new.filled_quantity,
                reason=new.reason,
            )
            self.updates.append(update)
            await self._emit(Topic.ORDERS_UPDATES, update, now)
        return new

    async def _reject_instruction(self, instruction: OrderInstruction, reason: str, now: datetime) -> None:
        update = OrderUpdate(
            instruction_id=instruction.instruction_id,
            child_order_id=make_tag(instruction.instruction_id, "parent"),
            state=OrderState.REJECTED,
            previous_state=OrderState.PENDING,
            filled_quantity=0,
            reason=reason[:500],
        )
        self.updates.append(update)
        await self._emit(Topic.ORDERS_UPDATES, update, now)

    # ================================================================ submission
    async def submit(self, instruction: OrderInstruction, now: datetime) -> InstructionRecord | None:
        if self.book.instruction(instruction.instruction_id) is not None:
            self.notes.append(f"duplicate instruction {instruction.instruction_id} ignored")
            return None
        try:
            self.signer.verify(instruction, now, self.state)
        except TokenReplayError as exc:  # a duplicate, not an error
            self.notes.append(f"replayed instruction {instruction.instruction_id} ignored: {exc}")
            return None
        except TokenError as exc:
            reason = str(exc)
            await self._reject_instruction(instruction, f"approval refused: {reason}", now)
            if isinstance(exc, TokenForgeryError):
                await self._alert(
                    AlertType.KILL_SWITCH,
                    f"instruction {instruction.instruction_id} failed authentication: {reason}",
                    now,
                )
            return None

        inst = instruction.instrument
        quote: Quote | None = None
        margin: float | None = None
        position = 0
        try:
            quote = self.broker.quote(inst)
            margin = self.broker.available_margin()
            position = sum(p.quantity for p in self.broker.positions() if p.instrument.key == inst.key)
            self._failures = 0
        except BrokerUnavailableError as exc:
            await self._broker_failure("pre-trade data", exc, now)
        except BrokerError as exc:  # e.g. no quote for this symbol: the checks reject it
            self.notes.append(f"{inst.key}: {exc}")
        price = (
            float(instruction.limit_price)
            if instruction.limit_price
            else (quote.last_price if quote else 0.0)
        )
        costs = (
            india_costs(_segment(inst), instruction.side.value, price, instruction.quantity, now.date()).total  # type: ignore[arg-type]
            if price > 0
            else 0.0
        )
        phase = self.market_clock.phase(now)
        reasons = pre_trade_checks(
            CheckInputs(
                instruction=instruction,
                now=now,
                phase=phase,
                next_open=self.market_clock.next_open(now),
                quote=quote,
                available_margin=margin,
                position=position,
                frozen=self.state.freeze_status().frozen,
                estimated_costs=costs,
                limits=self.limits.execution,
                allow_market_orders=self.broker_config.allow_market_orders,
                fno_margin_fraction=self.limits.execution.fno_margin_fraction,
            )
        )
        slices: tuple[int, ...] = ()
        interval = 0.0
        if not reasons:
            try:
                plan = slicer(
                    quantity=instruction.quantity,
                    lot_size=inst.lot_size,
                    price=price,
                    max_order_value=self.limits.execution.max_order_value_inr,
                    algo=instruction.algo.value,
                    horizon_minutes=instruction.horizon_minutes,
                    freeze_quantity=self._freeze_quantity(inst),
                )
                slices, interval = plan.slices, plan.interval_seconds
            except ValueError as exc:
                reasons.append(f"cannot slice: {exc}")
        record = InstructionRecord(
            instruction=instruction,
            status=InstructionStatus.REJECTED if reasons else InstructionStatus.ACCEPTED,
            received_at=now,
            arrival_price=quote.last_price if quote else None,
            reason="; ".join(reasons) or None,
        )
        self.book.add_instruction(record)
        if reasons:
            await self._reject_instruction(instruction, record.reason or "", now)
            return record

        kind = ChildKind.ENTRY if opens_risk(instruction) else ChildKind.EXIT
        for i, quantity in enumerate(slices):
            spacing = (
                0.0 if phase is MarketPhase.CLOSED else interval * i
            )  # after-market orders all queue for the open
            assert instruction.limit_price is not None
            self.book.add_child(
                ChildOrder(
                    child_order_id=make_tag(instruction.instruction_id, i),
                    instruction_id=instruction.instruction_id,
                    kind=kind,
                    slice_index=i,
                    instrument=inst,
                    side=instruction.side,
                    quantity=quantity,
                    limit_price=instruction.limit_price,
                    product=instruction.product,
                    not_before=now + timedelta(seconds=spacing),
                    updated_at=now,
                )
            )
        await self.release(now)
        return record

    def _freeze_quantity(self, instrument: Instrument) -> int | None:
        if instrument.instrument_type is InstrumentType.EQUITY:
            return None
        return self.limits.liquidity.freeze_quantity.get(instrument.symbol)

    # ================================================================ placement
    async def release(self, now: datetime) -> None:
        """Place every due child order that is not yet at the broker."""
        frozen = self.state.freeze_status().frozen
        for snapshot in sorted(self.book.children(), key=lambda c: (c.not_before, c.child_order_id)):
            child = self.book.child(snapshot.child_order_id) or snapshot  # earlier steps may have moved it
            if child.state is not OrderState.PENDING or child.outcome_unknown or child.not_before > now:
                continue
            if child.kind is ChildKind.ENTRY and frozen:
                await self._update(
                    child,
                    child.model_copy(
                        update={"state": OrderState.CANCELLED, "reason": "trading frozen", "updated_at": now}
                    ),
                    now,
                )
                continue
            if child.kind is ChildKind.EXIT and not await self._make_room(child, now):
                self.notes.append(f"{child.child_order_id}: exit held until protective orders are cancelled")
                continue
            await self._place(child, now)

    def _lookup(self, tag: str) -> BrokerOrder | Literal["unknown"] | None:
        try:
            return next((o for o in self.broker.orders() if o.tag == tag), None)
        except BrokerError:
            return "unknown"

    async def _place(self, child: ChildOrder, now: datetime) -> None:
        request = OrderRequest(
            tag=child.child_order_id,
            instrument=child.instrument,
            side=child.side,
            quantity=child.quantity,
            order_type=OrderType.LIMIT,
            price=child.limit_price,
            product=child.product,
            after_market=self.market_clock.phase(now) is MarketPhase.CLOSED,
        )
        existing = self._lookup(child.child_order_id)
        if isinstance(existing, BrokerOrder):  # placed earlier; the answer never reached us
            await self._adopt(child, existing.broker_order_id, now)
            return
        for attempt in range(self.max_attempts):
            await self._limiter.acquire()
            current = self.book.child(child.child_order_id)
            assert current is not None
            child = await self._update(
                current, current.model_copy(update={"attempts": current.attempts + 1, "updated_at": now}), now
            )
            try:
                broker_id = self.broker.place_order(request)
            except BrokerUnavailableError as exc:
                await self._broker_failure(f"place {child.child_order_id}", exc, now)
                await self.retry_delay(attempt)
                found = self._lookup(child.child_order_id)
                if isinstance(found, BrokerOrder):
                    await self._adopt(child, found.broker_order_id, now)
                    return
                if found == "unknown":
                    break  # cannot tell whether it is live: never send it again blind
                continue  # the broker does not have it: safe to retry with the same tag
            except BrokerError as exc:  # refused, or never sent (e.g. unknown symbol)
                self._failures = 0
                await self._update(
                    child,
                    child.model_copy(
                        update={
                            "state": OrderState.REJECTED,
                            "reason": f"broker: {exc}"[:500],
                            "updated_at": now,
                        }
                    ),
                    now,
                )
                return
            self._failures = 0
            await self._adopt(child, broker_id, now)
            return
        await self._update(
            child,
            child.model_copy(
                update={"outcome_unknown": True, "reason": "placement outcome unknown", "updated_at": now}
            ),
            now,
        )
        await self._alert(
            AlertType.BROKER_DISCONNECT,
            f"order {child.child_order_id} for {child.instrument.key}: "
            f"placement outcome unknown after {child.attempts} attempts",
            now,
        )

    async def _adopt(self, child: ChildOrder, broker_order_id: str, now: datetime) -> None:
        current = self.book.child(child.child_order_id)
        assert current is not None
        await self._update(
            current,
            current.model_copy(
                update={
                    "state": OrderState.PLACED if current.state is OrderState.PENDING else current.state,
                    "broker_order_id": broker_order_id,
                    "outcome_unknown": False,
                    "reason": None,
                    "updated_at": now,
                }
            ),
            now,
        )

    # ================================================================ protection
    def _entry_levels(self, key: str) -> InstructionRecord | None:
        entries = [
            r
            for r in self.book.instructions()
            if r.status is InstructionStatus.ACCEPTED
            and r.instruction.instrument.key == key
            and opens_risk(r.instruction)
            and r.instruction.protective_stop is not None
        ]
        return max(entries, key=lambda r: r.received_at) if entries else None

    def _cover(self, key: str) -> tuple[int, list[Protection]]:
        """(quantity already on its way out, live protections) for one instrument."""
        exiting = sum(
            c.quantity - c.filled_quantity
            for c in self.book.children()
            if c.instrument.key == key
            and c.kind is ChildKind.EXIT
            and c.open
            and c.state is not OrderState.PENDING
        )
        live = [p for p in self.book.protections() if p.instrument.key == key and p.live]
        return exiting + self._open_triggered.get(key, 0), live

    async def _cancel_protection(self, protection: Protection, now: datetime) -> bool:
        if protection.broker_protective_id is None:
            return False
        try:
            self.broker.cancel_protective(protection.broker_protective_id)
        except OrderRejectedError:
            pass  # already triggered or gone; the next sync records which
        except BrokerUnavailableError as exc:
            await self._broker_failure(f"cancel protection {protection.protection_id}", exc, now)
            return False
        self.book.put_protection(protection.model_copy(update={"status": "cancelled", "updated_at": now}))
        return True

    async def _make_room(self, exit_child: ChildOrder, now: datetime) -> bool:
        """Cancel protection so that stops plus exits never exceed the position."""
        key = exit_child.instrument.key
        held = abs(net_positions(self.book.fills()).get(key, 0))
        exiting, live = self._cover(key)
        excess = sum(p.quantity for p in live) + exiting + exit_child.quantity - held
        for p in sorted(live, key=lambda p: p.updated_at, reverse=True):
            if excess <= 0:
                break
            if await self._cancel_protection(p, now):
                excess -= p.quantity
        return excess <= 0

    async def ensure_protection(self, now: datetime) -> None:
        positions = net_positions(self.book.fills())
        keys = set(positions) | {p.instrument.key for p in self.book.protections() if p.live}
        for key in sorted(keys):
            qty = positions.get(key, 0)
            side = Side.SELL if qty > 0 else Side.BUY
            exiting, live = self._cover(key)
            pending_exits = sum(
                c.quantity
                for c in self.book.children()
                if c.instrument.key == key and c.kind is ChildKind.EXIT and c.state is OrderState.PENDING
            )
            need = max(abs(qty) - exiting - pending_exits, 0)
            wrong_side = [p for p in live if p.side is not side or qty == 0]
            for p in wrong_side:
                await self._cancel_protection(p, now)
            live = [p for p in live if p not in wrong_side]
            covered = sum(p.quantity for p in live)
            for p in sorted(live, key=lambda p: p.updated_at, reverse=True):
                if covered <= need:
                    break
                if await self._cancel_protection(p, now):
                    covered -= p.quantity
            if covered < need and qty != 0:
                await self._protect(key, side, need - covered, now)

    async def _protect(self, key: str, side: Side, quantity: int, now: datetime) -> None:
        source = self._entry_levels(key)
        if source is None:
            await self._alert(
                AlertType.RECONCILIATION_MISMATCH,
                f"{key}: position of unknown origin has no signed stop",
                now,
            )
            return
        ins = source.instruction
        assert ins.protective_stop is not None
        stop = float(ins.protective_stop)
        target = float(ins.targets[0]) if ins.targets else None
        if target is not None and ((target <= stop) if side is Side.SELL else (target >= stop)):
            target = None
        bps = self.limits.execution.protective_stop_limit_bps / 1e4
        stop_limit = tick(stop * (1 - bps)) if side is Side.SELL else tick(stop * (1 + bps))
        n = sum(1 for p in self.book.protections() if p.instruction_id == ins.instruction_id)
        protection = Protection(
            protection_id=make_tag(ins.instruction_id, "protect", n),
            instruction_id=ins.instruction_id,
            instrument=ins.instrument,
            side=side,
            quantity=quantity,
            stop_trigger=tick(stop),
            stop_limit=stop_limit,
            target=tick(target) if target is not None else None,
            product=ins.product,
            updated_at=now,
        )
        self.book.put_protection(protection)
        await self._limiter.acquire()
        try:
            broker_id = self.broker.place_protective(
                ProtectiveRequest(
                    tag=protection.protection_id,
                    instrument=protection.instrument,
                    side=side,
                    quantity=quantity,
                    stop_trigger=protection.stop_trigger,
                    stop_limit=protection.stop_limit,
                    target=protection.target,
                    product=protection.product,
                )
            )
        except BrokerUnavailableError as exc:
            await self._broker_failure(f"protect {key}", exc, now)
            return  # stays pending; the next sync adopts it or places it again
        except BrokerError as exc:
            self.book.put_protection(protection.model_copy(update={"status": "cancelled", "updated_at": now}))
            await self._alert(
                AlertType.LIQUIDITY_COLLAPSE, f"{key}: protective stop refused by the broker: {exc}", now
            )
            return
        self.book.put_protection(
            protection.model_copy(
                update={"status": "active", "broker_protective_id": broker_id, "updated_at": now}
            )
        )

    # ================================================================ synchronisation
    async def sync(self, now: datetime) -> None:
        try:
            orders = self.broker.orders()
            trades = self.broker.trades()
            protective = self.broker.protective_orders()
        except BrokerError as exc:
            await self._broker_failure("sync", exc, now)
            return
        self._failures = 0
        mismatches: list[str] = []
        by_id = {o.broker_order_id: o for o in orders}
        by_tag = {o.tag: o for o in orders if o.tag}
        await self._sync_protections(protective, by_id, now)
        for snapshot in self.book.children():
            child = self.book.child(snapshot.child_order_id) or snapshot
            if not child.open:
                continue
            bo = (
                by_id.get(child.broker_order_id)
                if child.broker_order_id
                else by_tag.get(child.child_order_id)
            )
            if bo is None:
                if child.state is not OrderState.PENDING:
                    mismatches.append(f"order {child.child_order_id} is unknown to the broker")
                elif child.outcome_unknown:  # the broker is reachable and does not have it
                    await self._update(
                        child, child.model_copy(update={"outcome_unknown": False, "updated_at": now}), now
                    )
                continue
            await self._apply(child, bo, now)
        mismatches += await self._book_trades(trades, by_id, now)
        await self.ensure_protection(now)
        await self.release(now)
        if mismatches:
            await self._alert(AlertType.RECONCILIATION_MISMATCH, "; ".join(mismatches), now)

    async def _sync_protections(
        self, protective: list[ProtectiveOrder], orders: dict[str, BrokerOrder], now: datetime
    ) -> None:
        by_id = {p.protective_id: p for p in protective}
        adopted = {p.broker_protective_id for p in self.book.protections() if p.broker_protective_id}
        self._open_triggered = defaultdict(int)
        for rec in self.book.protections():
            if rec.status == "triggered" and rec.triggered_order_id in orders:
                order = orders[rec.triggered_order_id]
                if order.status in (BrokerOrderStatus.OPEN, BrokerOrderStatus.TRIGGER_PENDING):
                    self._open_triggered[rec.instrument.key] += order.quantity - order.filled_quantity
                elif (
                    order.filled_quantity < order.quantity and rec.protection_id not in self._alerted_triggers
                ):
                    self._alerted_triggers.add(rec.protection_id)
                    await self._alert(
                        AlertType.LIQUIDITY_COLLAPSE,
                        f"{rec.instrument.key}: stop at {rec.stop_trigger} triggered but only "
                        f"{order.filled_quantity}/{order.quantity} filled "
                        f"({order.status_message or order.status.value})",
                        now,
                    )
            if not rec.live:
                continue
            bp = by_id.get(rec.broker_protective_id) if rec.broker_protective_id else None
            if bp is None and rec.broker_protective_id is None:
                bp = next(
                    (
                        p
                        for p in protective
                        if p.protective_id not in adopted
                        and (p.tag == rec.protection_id or (p.tag is None and self._same_levels(p, rec)))
                    ),
                    None,
                )
                if bp is None:  # never reached the broker: let ensure_protection place a fresh one
                    self.book.put_protection(
                        rec.model_copy(update={"status": "cancelled", "updated_at": now})
                    )
                    continue
                adopted.add(bp.protective_id)
                rec = rec.model_copy(
                    update={"broker_protective_id": bp.protective_id, "status": "active", "updated_at": now}
                )
                self.book.put_protection(rec)
            if bp is None:
                await self._alert(
                    AlertType.RECONCILIATION_MISMATCH,
                    f"protection {rec.protection_id} is missing at the broker",
                    now,
                )
                self.book.put_protection(rec.model_copy(update={"status": "cancelled", "updated_at": now}))
                continue
            if bp.status is ProtectiveStatus.TRIGGERED:
                self.book.put_protection(
                    rec.model_copy(
                        update={
                            "status": "triggered",
                            "triggered_order_id": bp.triggered_order_id,
                            "updated_at": now,
                        }
                    )
                )
                fired = orders.get(bp.triggered_order_id or "")
                if fired is not None and fired.status in (
                    BrokerOrderStatus.OPEN,
                    BrokerOrderStatus.TRIGGER_PENDING,
                ):
                    self._open_triggered[rec.instrument.key] += fired.quantity - fired.filled_quantity
            elif bp.status is ProtectiveStatus.CANCELLED:
                self.notes.append(f"protection {rec.protection_id} was cancelled at the broker")
                self.book.put_protection(rec.model_copy(update={"status": "cancelled", "updated_at": now}))

    @staticmethod
    def _same_levels(p: ProtectiveOrder, rec: Protection) -> bool:
        return (
            p.instrument.key == rec.instrument.key
            and p.side is rec.side
            and p.quantity == rec.quantity
            and p.stop_trigger == rec.stop_trigger
            and p.target == rec.target
        )

    async def _apply(self, child: ChildOrder, bo: BrokerOrder, now: datetime) -> None:
        if child.broker_order_id is None:
            await self._adopt(child, bo.broker_order_id, now)
            child = self.book.child(child.child_order_id) or child
        target = {
            BrokerOrderStatus.COMPLETE: OrderState.FILLED,
            BrokerOrderStatus.CANCELLED: OrderState.CANCELLED,
            BrokerOrderStatus.REJECTED: OrderState.REJECTED,
        }.get(bo.status, OrderState.PARTIALLY_FILLED if bo.filled_quantity else OrderState.PLACED)
        if target is OrderState.PLACED and child.state is OrderState.PLACED:
            return
        if target is child.state and bo.filled_quantity == child.filled_quantity:
            return
        if child.state is OrderState.PENDING and target is not OrderState.PLACED:
            child = await self._update(
                child, child.model_copy(update={"state": OrderState.PLACED, "updated_at": now}), now
            )
        await self._update(
            child,
            child.model_copy(
                update={
                    "state": target,
                    "filled_quantity": bo.filled_quantity,
                    "average_price": bo.average_price,
                    "reason": bo.status_message
                    if target in (OrderState.CANCELLED, OrderState.REJECTED)
                    else None,
                    "updated_at": now,
                }
            ),
            now,
        )

    async def _book_trades(
        self, trades: list[BrokerTrade], orders: dict[str, BrokerOrder], now: datetime
    ) -> list[str]:
        mismatches = []
        children = {c.broker_order_id: c for c in self.book.children() if c.broker_order_id}
        triggered = {p.triggered_order_id: p for p in self.book.protections() if p.triggered_order_id}
        booked = {f.broker_trade_id for f in self.book.fills()}
        for t in trades:
            if t.trade_id in booked:
                continue
            if (child := children.get(t.broker_order_id)) is not None:
                record = self.book.instruction(child.instruction_id)
                arrival = (
                    record.arrival_price if record and record.arrival_price else float(child.limit_price)
                )
                instruction_id, child_id = child.instruction_id, child.child_order_id
            elif (protection := triggered.get(t.broker_order_id)) is not None:
                order = orders.get(t.broker_order_id)
                is_target = (
                    order is not None and protection.target is not None and order.price == protection.target
                )
                arrival = float(
                    protection.target if is_target and protection.target else protection.stop_trigger
                )
                instruction_id, child_id = protection.instruction_id, protection.protection_id
            else:
                mismatches.append(f"trade {t.trade_id} ({t.instrument.key}) matches no order of this system")
                continue
            sign = 1 if t.side is Side.BUY else -1
            fill = Fill(
                instruction_id=instruction_id,
                child_order_id=child_id,
                broker_trade_id=t.trade_id,
                instrument=t.instrument,
                side=t.side,
                quantity=t.quantity,
                price=Decimal(str(round(t.price, 4))),
                filled_at=t.filled_at,
                arrival_price=Decimal(str(round(arrival, 4))),
                slippage_bps=sign * (t.price - arrival) / arrival * 1e4,
            )
            if self.book.add_fill(fill):
                self.published_fills.append(fill)
                await self._emit(Topic.ORDERS_FILLS, fill, now)
        return mismatches

    # ================================================================ alerts and reconciliation
    async def on_alert(self, alert: RiskAlert, now: datetime) -> None:
        if alert.clears_alert_id is not None or not alert.freeze_new_orders:
            return
        await self._freeze(alert, now)

    async def _freeze(self, alert: RiskAlert, now: datetime) -> None:
        """Freeze new entries and cancel resting entry orders. Exits and stops stay."""
        self.state.set_frozen(alert.alert_id, now)
        for snapshot in self.book.children():
            child = self.book.child(snapshot.child_order_id) or snapshot
            if child.kind is not ChildKind.ENTRY or not child.open:
                continue
            if child.state is OrderState.PENDING and not child.outcome_unknown:
                await self._update(
                    child,
                    child.model_copy(
                        update={
                            "state": OrderState.CANCELLED,
                            "reason": f"freeze: {alert.alert_type.value}",
                            "updated_at": now,
                        }
                    ),
                    now,
                )
            elif child.broker_order_id is not None:
                try:
                    self.broker.cancel_order(child.broker_order_id)
                except OrderRejectedError:
                    pass  # already filled or cancelled; sync records which
                except BrokerUnavailableError as exc:
                    self.notes.append(f"cancel {child.child_order_id} failed: {exc}")

    async def reconcile(self, now: datetime) -> ReconciliationReport:
        await self.sync(now)
        try:
            broker_positions = self.broker.positions()
            orders = self.broker.orders()
        except BrokerError as exc:
            await self._broker_failure("reconcile", exc, now)
            return ReconciliationReport(at=now, matched=False, position_differences={}, error=str(exc))
        ledger = net_positions(self.book.fills())
        broker: dict[str, int] = defaultdict(int)
        for p in broker_positions:
            broker[p.instrument.key] += p.quantity
        diffs = {
            k: (ledger.get(k, 0), broker.get(k, 0))
            for k in sorted(set(ledger) | set(broker))
            if ledger.get(k, 0) != broker.get(k, 0)
        }
        unknown = tuple(
            o.broker_order_id
            for o in orders
            if o.tag
            and o.tag.startswith(TAG_PREFIX)
            and self.book.child(o.tag) is None
            and o.status in (BrokerOrderStatus.OPEN, BrokerOrderStatus.TRIGGER_PENDING)
        )
        report = ReconciliationReport(
            at=now, matched=not diffs and not unknown, position_differences=diffs, unknown_orders=unknown
        )
        if not report.matched:
            detail = "; ".join(f"{k}: ledger {a} vs broker {b}" for k, (a, b) in diffs.items())
            if unknown:
                detail += f"; unknown open orders {', '.join(unknown)}"
            await self._alert(AlertType.RECONCILIATION_MISMATCH, detail.strip("; "), now)
        return report
