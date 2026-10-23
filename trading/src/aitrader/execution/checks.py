"""Pre-trade checks, market hours and the order rate limiter (all deterministic).

Every check runs before any broker call, after the approval token has been verified. A
failed check rejects the whole instruction; nothing is partially sent. Freezes block only
orders that add risk: exits and reductions always pass that check, so positions can be cut.
"""

from __future__ import annotations

import asyncio
import time
from collections.abc import Awaitable, Callable, Iterable
from dataclasses import dataclass
from datetime import date, datetime, timedelta
from datetime import time as dtime
from enum import StrEnum

from aitrader.agents.data.calendar import IST, SESSION_CLOSE, SESSION_OPEN, ist_datetime
from aitrader.config.settings import ExecutionLimits
from aitrader.execution.broker import Quote
from aitrader.schemas.base import InstrumentType
from aitrader.schemas.orders import InstructionIntent, OrderInstruction, OrderType, Product, Side

PRE_OPEN = dtime(9, 0)


class MarketPhase(StrEnum):
    PRE_OPEN = "pre_open"  # 09:00-09:15 IST: orders collect for the opening auction
    OPEN = "open"
    CLOSED = "closed"  # after hours, weekends and holidays: orders go in as after-market orders


class MarketClock:
    """NSE session times on trading days. ``sessions`` (when given) is the exact set of
    trading days, as the paper runner knows it; other days are weekdays minus ``holidays``."""

    def __init__(self, holidays: Iterable[date] = (), sessions: Iterable[date] | None = None) -> None:
        self._holidays = frozenset(holidays)
        self._sessions = frozenset(sessions) if sessions is not None else None
        self._last_session = max(self._sessions) if self._sessions else None

    def is_session(self, day: date) -> bool:
        if self._sessions is not None and (self._last_session is None or day <= self._last_session):
            return day in self._sessions
        # Beyond the known sessions (the paper runner's last day), assume weekdays.
        return day.weekday() < 5 and day not in self._holidays

    def phase(self, now: datetime) -> MarketPhase:
        local = now.astimezone(IST)
        if not self.is_session(local.date()):
            return MarketPhase.CLOSED
        t = local.time()
        if PRE_OPEN <= t < SESSION_OPEN:
            return MarketPhase.PRE_OPEN
        if SESSION_OPEN <= t < SESSION_CLOSE:
            return MarketPhase.OPEN
        return MarketPhase.CLOSED

    def next_open(self, now: datetime) -> datetime:
        """The next session open, or ``now`` while the market is open."""
        day = now.astimezone(IST).date()
        for i in range(15):
            candidate = day + timedelta(days=i)
            if not self.is_session(candidate):
                continue
            if now < ist_datetime(candidate, SESSION_OPEN):
                return ist_datetime(candidate, SESSION_OPEN)
            if now < ist_datetime(candidate, SESSION_CLOSE):
                return now
        raise ValueError("no trading session in the next 15 days")


class RateLimiter:
    """Token bucket: at most ``per_second`` broker requests in any one-second window."""

    def __init__(
        self,
        per_second: int,
        monotonic: Callable[[], float] = time.monotonic,
        sleep: Callable[[float], Awaitable[None]] = asyncio.sleep,
    ) -> None:
        self._rate = per_second
        self._tokens = float(per_second)
        self._monotonic = monotonic
        self._sleep = sleep
        self._last = monotonic()
        self.waits = 0

    async def acquire(self) -> None:
        while True:
            now = self._monotonic()
            self._tokens = min(self._rate, self._tokens + (now - self._last) * self._rate)
            self._last = now
            if self._tokens >= 1:
                self._tokens -= 1
                return
            self.waits += 1
            await self._sleep((1 - self._tokens) / self._rate)


@dataclass(frozen=True)
class CheckInputs:
    instruction: OrderInstruction
    now: datetime
    phase: MarketPhase
    next_open: datetime
    quote: Quote | None
    available_margin: float | None
    position: int  # broker net position in this instrument
    frozen: bool
    estimated_costs: float
    limits: ExecutionLimits
    allow_market_orders: bool
    fno_margin_fraction: float


def opens_risk(instruction: OrderInstruction) -> bool:
    return instruction.intent in (InstructionIntent.OPEN, InstructionIntent.HEDGE)


def pre_trade_checks(x: CheckInputs) -> list[str]:
    """Reasons to refuse the instruction; empty when every check passes."""
    ins = x.instruction
    fails: list[str] = []
    if ins.instrument.instrument_type is InstrumentType.INDEX:
        fails.append("indices are not tradable")
    if ins.order_type is OrderType.MARKET and not x.allow_market_orders:
        fails.append("market orders are disabled; marketable limits only")
    if ins.valid_until <= x.now or (x.phase is MarketPhase.CLOSED and ins.valid_until <= x.next_open):
        fails.append("instruction expires before it can trade")
    if x.frozen and opens_risk(ins):
        fails.append("trading frozen: only exits and reductions are accepted")
    if ins.side is Side.SELL and ins.intent is InstructionIntent.OPEN and ins.product is Product.CNC:
        fails.append("cash-segment shorts are intraday only")
    if x.quote is None:
        return [*fails, "no quote available"]
    limit = float(ins.limit_price) if ins.limit_price is not None else x.quote.last_price
    if not x.quote.lower_circuit <= limit <= x.quote.upper_circuit:
        fails.append(
            f"limit {limit:.2f} outside the price band "
            f"[{x.quote.lower_circuit:.2f}, {x.quote.upper_circuit:.2f}]"
        )
    dev = x.limits.max_price_deviation_bps / 1e4
    last = x.quote.last_price
    if (ins.side is Side.BUY and limit > last * (1 + dev)) or (
        ins.side is Side.SELL and limit < last * (1 - dev)
    ):
        fails.append(
            f"limit {limit:.2f} deviates more than {x.limits.max_price_deviation_bps:.0f} bps from {last:.2f}"
        )
    if opens_risk(ins):
        if (ins.side is Side.BUY and x.position < 0) or (ins.side is Side.SELL and x.position > 0):
            fails.append("would flip an existing position; close it first")
        notional = ins.quantity * limit
        if ins.instrument.instrument_type is InstrumentType.FUTURE or (
            ins.instrument.instrument_type is InstrumentType.OPTION and ins.side is Side.SELL
        ):
            required = notional * x.fno_margin_fraction
        else:
            required = notional
        required += x.estimated_costs
        if x.available_margin is None:
            fails.append("margin unavailable")
        elif required > x.available_margin:
            fails.append(f"insufficient margin: need {required:,.0f}, have {x.available_margin:,.0f}")
    elif ins.intent is InstructionIntent.CLOSE:
        held = x.position if ins.side is Side.SELL else -x.position
        if held < ins.quantity:
            fails.append(f"close of {ins.quantity} exceeds the position of {max(held, 0)}")
    return fails
