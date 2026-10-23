"""TCA agent: realised execution cost per instruction against the pre-trade estimate.

Once every child order of an instruction is final, its fills are compared with the arrival
price (the last price when execution received the instruction) and with the liquidity
agent's estimate (half the spread plus the estimated impact). The estimation error is what
recalibrates the impact model. Fills of broker-held stops and targets are excluded: their
reference is the trigger level, not an arrival price. There is no intraday data yet, so no
VWAP benchmark is reported.
"""

from __future__ import annotations

from datetime import datetime
from uuid import UUID, uuid5

from aitrader.agents.publishing import publish
from aitrader.bus.interface import MessageBus
from aitrader.execution.orderbook import InstructionStatus, OrderBook
from aitrader.schemas.base import AgentId, StrictModel
from aitrader.schemas.envelope import Envelope, Topic
from aitrader.schemas.feedback import TcaResult
from aitrader.schemas.gates import LiquidityPlan
from aitrader.tools.microstructure import tca

_NS = UUID("8e2f1a0b-7c6d-4e5f-9a8b-1c2d3e4f5a6b")


class TcaState(StrictModel):
    plans: tuple[LiquidityPlan, ...] = ()
    done: tuple[UUID, ...] = ()
    results: tuple[TcaResult, ...] = ()


class TcaAgent:
    def __init__(self, bus: MessageBus, book: OrderBook, assumed_spread_bps: float) -> None:
        self._bus = bus
        self._book = book
        self._assumed_spread_bps = assumed_spread_bps
        self._plans: dict[UUID, LiquidityPlan] = {}
        self._done: set[UUID] = set()
        self.results: list[TcaResult] = []

    def state(self) -> TcaState:
        return TcaState(
            plans=tuple(self._plans.values()),
            done=tuple(sorted(self._done, key=str)),
            results=tuple(self.results),
        )

    def restore(self, state: TcaState) -> None:
        self._plans = {p.decision_id: p for p in state.plans}
        self._done = set(state.done)
        self.results = list(state.results)

    def attach(self, bus: MessageBus) -> None:
        async def on_plan(envelope: Envelope) -> None:
            if isinstance(envelope.payload, LiquidityPlan):
                self._plans[envelope.payload.decision_id] = envelope.payload

        bus.subscribe((Topic.LIQUIDITY_PLAN,), on_plan, group="tca")

    async def run(self, now: datetime) -> list[TcaResult]:
        out = []
        fills = self._book.fills()
        for record in self._book.instructions():
            ins = record.instruction
            if record.status is not InstructionStatus.ACCEPTED or ins.instruction_id in self._done:
                continue
            children = self._book.children(ins.instruction_id)
            if not children or any(c.open for c in children):
                continue
            self._done.add(ins.instruction_id)
            ids = {c.child_order_id for c in children}
            mine = [f for f in fills if f.instruction_id == ins.instruction_id and f.child_order_id in ids]
            if not mine or record.arrival_price is None:
                continue
            plan = self._plans.get(ins.decision_id)
            estimate = plan.spread_bps / 2 + plan.est_impact_bps if plan else self._assumed_spread_bps / 2
            report = tca(
                side=ins.side.value,
                fill_prices=[float(f.price) for f in mine],
                fill_quantities=[f.quantity for f in mine],
                arrival_price=record.arrival_price,
                estimated_cost_bps=estimate,
            )
            result = TcaResult(
                instruction_id=ins.instruction_id,
                instrument=ins.instrument,
                filled_quantity=int(report.filled_quantity),
                avg_price=report.avg_price,
                arrival_price=record.arrival_price,
                realised_slippage_bps=report.slippage_vs_arrival_bps,
                estimated_impact_bps=estimate,
                estimation_error_bps=report.estimation_error_bps or 0.0,
            )
            await publish(
                self._bus,
                message_id=uuid5(_NS, str(ins.instruction_id)),
                topic=Topic.TCA_RESULTS,
                agent_id=AgentId.TCA,
                payload=result,
                available_at=now,
                produced_at=now,
            )
            self.results.append(result)
            out.append(result)
        return out
