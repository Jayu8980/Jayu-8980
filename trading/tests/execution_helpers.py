"""A hand-built market and a wired execution agent for Phase 6 tests.

``XYZ`` trades at 100 every day unless a test overrides a session's (open, high, low, close),
which is how gaps, stop hits and target hits are staged.
"""

from __future__ import annotations

import asyncio
from collections.abc import Awaitable
from dataclasses import dataclass, field
from datetime import UTC, date, datetime, time, timedelta
from decimal import Decimal
from typing import Any, TypeVar
from uuid import UUID, uuid4

from aitrader.agents.data.agent import MarketData
from aitrader.agents.data.calendar import ist_datetime, next_session_close
from aitrader.agents.risk.tokens import TokenSigner
from aitrader.audit import InMemoryAuditLog
from aitrader.backtest.strategies import equity
from aitrader.bus import InMemoryBus
from aitrader.config import load_risk_limits, load_system_config
from aitrader.config.settings import RiskLimits
from aitrader.execution.agent import ExecutionAgent
from aitrader.execution.checks import MarketClock, RateLimiter
from aitrader.execution.orderbook import InMemoryOrderBook, OrderBook
from aitrader.execution.paper import PaperBroker
from aitrader.paper.runner import SimClock
from aitrader.schemas.decision import Action
from aitrader.schemas.envelope import Envelope, Topic
from aitrader.schemas.gates import ExecAlgo
from aitrader.schemas.orders import (
    InstructionIntent,
    OrderInstruction,
    OrderType,
    Product,
    Side,
    UnsignedInstruction,
)
from aitrader.state import InMemoryStateStore
from aitrader.state.interface import StateStore
from aitrader.storage import CachedPitReader, PitStore
from tests.backtest_helpers import bar_record
from tests.data_helpers import NIFTY, eod, weekdays

T = TypeVar("T")
KEY = b"phase6-test-signing-key-32-bytes!!"
XYZ = equity("XYZ")
START = date(2025, 1, 6)
Ohlc = tuple[float, float, float, float]


def run(coro: Awaitable[T]) -> T:
    return asyncio.run(coro)  # type: ignore[arg-type]


async def no_wait(_: int) -> None:
    return None


def build_store(
    n: int = 30, overrides: dict[int, Ohlc] | None = None, volume: int = 1_000_000
) -> tuple[PitStore, list[date]]:
    store = PitStore()
    days = weekdays(START, n)
    overrides = overrides or {}
    rows = []
    for i, d in enumerate(days):
        rows.append(bar_record(NIFTY, d, 20000, 20100, 19900, 20000))
        o, h, lo, c = overrides.get(i, (100.0, 101.0, 99.0, 100.0))
        rows.append(bar_record(XYZ, d, o, h, lo, c, volume))
    store.write(rows, ingested_at=eod(days[-1]))
    return store, days


@dataclass
class Rig:
    store: PitStore
    days: list[date]
    limits: RiskLimits = field(default_factory=load_risk_limits)
    state: StateStore = field(default_factory=InMemoryStateStore)
    book: OrderBook = field(default_factory=InMemoryOrderBook)
    cash: float = 1_000_000.0

    def __post_init__(self) -> None:
        self.clock = SimClock(ist_datetime(self.days[0], time(8, 0)))
        self.audit = InMemoryAuditLog(lambda: datetime(2030, 1, 1, tzinfo=UTC))
        self.bus = InMemoryBus(self.audit)
        self.market = MarketData(CachedPitReader(self.store))
        self.broker = PaperBroker(self.market, self.clock, cash=self.cash)
        self.signer = TokenSigner(
            KEY, ttl=timedelta(seconds=self.limits.execution.approval_token_ttl_seconds)
        )
        self.agent = ExecutionAgent(
            broker=self.broker,
            signer=self.signer,
            state=self.state,
            book=self.book,
            bus=self.bus,
            limits=self.limits,
            broker_config=load_system_config().broker,
            market_clock=MarketClock(sessions=self.days),
            rate_limiter=RateLimiter(1000),
            retry_delay=no_wait,
        )
        self.agent.attach(self.bus)
        self.envelopes: list[Envelope] = []

        async def record(envelope: Envelope) -> None:
            self.envelopes.append(envelope)

        self.bus.subscribe(list(Topic), record, group="test-recorder")

    def at(self, i: int, hh: int, mm: int = 0) -> datetime:
        self.clock.now = ist_datetime(self.days[i], time(hh, mm))
        return self.clock.now

    def evening(self, i: int) -> datetime:
        return self.at(i, 20, 0)

    def instruction(
        self,
        i: int,
        *,
        side: Side = Side.BUY,
        quantity: int = 100,
        limit: str = "101.00",
        stop: str | None = "95.00",
        targets: tuple[str, ...] = ("110.00",),
        intent: InstructionIntent = InstructionIntent.OPEN,
        signer: TokenSigner | None = None,
        decision_id: UUID | None = None,
        **kw: Any,
    ) -> OrderInstruction:
        issued = ist_datetime(self.days[i], time(20, 0))
        action = (
            Action.BUY
            if intent is InstructionIntent.OPEN and side is Side.BUY
            else Action.SELL
            if side is Side.SELL
            else Action.REDUCE
        )
        unsigned = UnsignedInstruction(
            instruction_id=uuid4(),
            idempotency_key=f"test-{uuid4()}",
            decision_id=decision_id or uuid4(),
            action=action,
            intent=intent,
            instrument=XYZ,
            side=side,
            quantity=quantity,
            order_type=OrderType.LIMIT,
            limit_price=Decimal(limit),
            product=Product.CNC,
            algo=ExecAlgo.MARKETABLE_LIMIT,
            participation_rate=0.1,
            horizon_minutes=30,
            protective_stop=Decimal(stop) if stop and intent is InstructionIntent.OPEN else None,
            targets=tuple(Decimal(t) for t in targets) if intent is InstructionIntent.OPEN else (),
            max_price_deviation_bps=100,
            valid_until=next_session_close(issued),
            **kw,
        )
        token = (signer or self.signer).sign(unsigned, issued)
        return OrderInstruction(**unsigned.model_dump(), approval=token)

    def submit(self, instruction: OrderInstruction, now: datetime | None = None) -> Any:
        now = now or instruction.approval.issued_at + timedelta(seconds=1)
        self.clock.now = now
        return run(self.agent.submit(instruction, now))

    def session(self, i: int) -> None:
        """Run session ``i`` the way the paper runner does."""
        self.at(i, 9, 15)
        self.broker.open_session(self.days[i])
        run(self.agent.sync(self.at(i, 9, 16)))
        self.at(i, 15, 30)
        self.broker.run_session(self.days[i])
        run(self.agent.sync(self.at(i, 15, 31)))

    def position(self) -> int:
        return sum(p.quantity for p in self.broker.positions())

    def live_protection(self) -> int:
        return sum(p.quantity for p in self.book.protections() if p.live)


def rig(n: int = 30, overrides: dict[int, Ohlc] | None = None, **kw: Any) -> Rig:
    store, days = build_store(n, overrides)
    return Rig(store, days, **kw)
