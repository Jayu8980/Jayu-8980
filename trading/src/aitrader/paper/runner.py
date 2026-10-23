"""Paper trading: the full decision chain and the real execution path against the paper broker.

One ``run_day`` per session, on a simulated clock (IST):
- 09:00  kill-file check; signed exits for any pending flatten request
- 09:15  the paper broker opens the session: queued after-market orders trade at the open
- 09:16  execution syncs: fills booked, protective stops placed for new positions
- 15:30  the paper broker simulates the rest of the session and expires day orders
- 15:31  execution reconciles its ledger with the broker; TCA runs on finished instructions
- decision time (after EOD data is published): kill-file check, then one decision cycle on
  the broker's portfolio. Its instructions reach execution over the bus, are verified and
  queued as after-market orders for the next open.

Nothing here bypasses the bus: execution receives instructions and alerts only as bus
messages, so this runs unchanged on Redis Streams (``drain`` delivers between steps).
Daily records feed the monitoring dashboard. In live paper mode, the same steps would run
on a wall-clock scheduler, with the paper broker replaced by live quotes; that is Phase 7.
"""

from __future__ import annotations

from collections.abc import Sequence
from dataclasses import dataclass, field
from datetime import date, datetime, time, timedelta
from uuid import UUID, uuid5

from aitrader.agents.data.agent import MarketData
from aitrader.agents.data.calendar import SESSION_CLOSE, SESSION_OPEN, ist_datetime
from aitrader.agents.publishing import publish
from aitrader.agents.risk.agent import RiskAgent
from aitrader.agents.risk.portfolio import Holding, PortfolioSnapshot
from aitrader.audit.interface import AuditLog
from aitrader.bus.interface import MessageBus
from aitrader.execution.agent import ExecutionAgent, ReconciliationReport
from aitrader.execution.checks import PRE_OPEN
from aitrader.execution.killswitch import KillSwitch
from aitrader.execution.paper import PaperBroker
from aitrader.feedback.tca import TcaAgent
from aitrader.orchestrator.cycle import CycleResult, DecisionCycle
from aitrader.schemas.audit import LlmCallRecord
from aitrader.schemas.base import AgentId, Instrument
from aitrader.schemas.envelope import Envelope, Topic
from aitrader.schemas.feedback import TcaResult
from aitrader.schemas.orders import Fill
from aitrader.schemas.risk import RiskAlert
from aitrader.state.interface import StateStore

_NS = UUID("b1c2d3e4-f5a6-4b7c-8d9e-0f1a2b3c4d5e")


class SimClock:
    def __init__(self, start: datetime) -> None:
        self.now = start

    def __call__(self) -> datetime:
        return self.now


@dataclass
class DayRecord:
    day: date
    equity: float
    cash: float
    gross_exposure: float
    net_exposure: float
    daily_drawdown: float
    total_drawdown: float
    positions: dict[str, int]
    prices: dict[str, float]
    fills: list[Fill]
    instructions: int
    rejected_instructions: int
    alerts: list[RiskAlert]
    frozen: bool
    reconciliation: ReconciliationReport | None
    limit_multiplier: float
    decisions: int
    llm_cost_usd: float
    agent_status: dict[str, str]
    agent_latency_ms: dict[str, float]
    tca: list[TcaResult]
    notes: list[str] = field(default_factory=list)


@dataclass
class PaperTrader:
    cycle: DecisionCycle
    risk: RiskAgent
    execution: ExecutionAgent
    broker: PaperBroker
    kill_switch: KillSwitch
    tca: TcaAgent
    bus: MessageBus
    state: StateStore
    market: MarketData
    audit: AuditLog
    clock: SimClock
    instruments: Sequence[Instrument] = ()
    sectors: dict[str, str] = field(default_factory=dict)
    decision_time: time = time(20, 0)
    records: list[DayRecord] = field(default_factory=list)

    def __post_init__(self) -> None:
        self._alerts: list[RiskAlert] = []
        self._flattened: set[UUID] = set()
        self._peak = self.broker.equity()
        self._day_start = self._peak
        self._audit_seen = 0
        self._instructions_seen = 0
        self._fills_seen = 0
        self.execution.attach(self.bus)
        self.tca.attach(self.bus)

        async def on_alert(envelope: Envelope) -> None:
            if isinstance(envelope.payload, RiskAlert):
                self._alerts.append(envelope.payload)

        self.bus.subscribe((Topic.RISK_ALERTS,), on_alert, group="paper_runner")

    def _at(self, day: date, at: time) -> datetime:
        self.clock.now = ist_datetime(day, at)
        return self.clock.now

    def snapshot(self, now: datetime) -> PortfolioSnapshot:
        holdings = []
        for p in sorted(self.broker.positions(), key=lambda p: p.instrument.key):
            price = self.broker.last_price(p.instrument) or p.average_price
            holdings.append(Holding(p.instrument, p.quantity, price, self.sectors.get(p.instrument.symbol)))
        equity = self.broker.equity()
        self._peak = max(self._peak, equity)
        return PortfolioSnapshot(
            as_of=now,
            equity=equity,
            cash=self.broker.cash,
            day_start_equity=self._day_start,
            peak_equity=self._peak,
            holdings=tuple(holdings),
        )

    async def _flatten(self, now: datetime) -> None:
        """Turn any flatten request into risk-signed exits, once per alert."""
        for alert in self._alerts:
            if not alert.flatten_positions or alert.alert_id in self._flattened:
                continue
            self._flattened.add(alert.alert_id)
            for instruction in self.risk.flatten_instructions(alert, self.snapshot(now), now):
                await publish(
                    self.bus,
                    message_id=uuid5(_NS, str(instruction.instruction_id)),
                    topic=Topic.ORDERS_INSTRUCTIONS,
                    agent_id=AgentId.RISK,
                    payload=instruction,
                    available_at=now,
                    produced_at=now,
                )
        await self.bus.drain()

    def _llm_cost(self, cycle_id: UUID) -> float:
        records = self.audit.records(self._audit_seen)
        self._audit_seen += len(records)
        total = 0.0
        for r in records:
            if r.record_type == "llm_call":
                call = LlmCallRecord.model_validate_json(r.body_json)
                if call.cycle_id == cycle_id:
                    total += call.cost_usd
        return total

    async def run_day(self, day: date) -> DayRecord:
        alerts_before = len(self._alerts)
        await self.kill_switch.poll(self._at(day, PRE_OPEN))
        await self.bus.drain()
        await self._flatten(self.clock.now)

        self._at(day, SESSION_OPEN)
        self.broker.open_session(day)
        await self.execution.sync(self._at(day, time(9, 16)))
        await self.bus.drain()

        self._at(day, SESSION_CLOSE)
        self.broker.run_session(day)
        report = await self.execution.reconcile(self._at(day, time(15, 31)))
        tca = await self.tca.run(self.clock.now)
        await self.bus.drain()

        now = self._at(day, self.decision_time)
        await self.kill_switch.poll(now)
        await self.bus.drain()
        await self._flatten(now)
        portfolio = self.snapshot(now)
        held = [h.instrument for h in portfolio.holdings]
        universe = list({i.key: i for i in [*self.instruments, *held]}.values())
        result: CycleResult = await self.cycle.run(now, universe, portfolio)
        await self.bus.drain()
        await self._flatten(now + timedelta(seconds=2))

        records = self.execution.book.instructions()
        new_records = records[self._instructions_seen :]
        self._instructions_seen = len(records)
        fills = self.execution.book.fills()
        new_fills = fills[self._fills_seen :]
        self._fills_seen = len(fills)
        record = DayRecord(
            day=day,
            equity=portfolio.equity,
            cash=portfolio.cash,
            gross_exposure=portfolio.gross / portfolio.equity,
            net_exposure=portfolio.net / portfolio.equity,
            daily_drawdown=portfolio.daily_drawdown,
            total_drawdown=portfolio.total_drawdown,
            positions={h.instrument.key: h.quantity for h in portfolio.holdings},
            prices={h.instrument.key: h.price for h in portfolio.holdings},
            fills=new_fills,
            instructions=sum(1 for r in new_records if r.status.value == "accepted"),
            rejected_instructions=sum(1 for r in new_records if r.status.value == "rejected"),
            alerts=self._alerts[alerts_before:],
            frozen=self.state.freeze_status().frozen,
            reconciliation=report,
            limit_multiplier=result.limit_multiplier,
            decisions=len(result.decisions),
            llm_cost_usd=self._llm_cost(result.cycle_id),
            agent_status={a.value: s.status.value for a, s in result.statuses.items()},
            agent_latency_ms={a.value: s.latency_ms for a, s in result.statuses.items()},
            tca=tca,
            notes=list(result.notes),
        )
        self._day_start = portfolio.equity
        self.records.append(record)
        return record

    async def run(self, days: Sequence[date]) -> list[DayRecord]:
        return [await self.run_day(day) for day in days]


def session_window(market: MarketData, start: date, end: date) -> list[date]:
    sessions = market.sessions(ist_datetime(end, time(23, 59)), start=ist_datetime(start, time(0, 0)))
    return [d for d in sessions if start <= d <= end]


__all__ = ["DayRecord", "PaperTrader", "SimClock", "session_window"]
