"""The full multi-agent decision chain as a backtest ``Strategy``.

At each decision point the chain runs one ``DecisionCycle`` on the portfolio as the engine
sees it. Each resulting instruction is verified exactly as the execution agent will verify
it: valid signature, matching digest, unexpired, token and idempotency key not used before.
Only then does it become an engine order. An instruction failing any check is dropped and
logged, so the backtest exercises the same no-bypass path as live trading.
"""

from __future__ import annotations

import asyncio
from collections.abc import Sequence
from dataclasses import dataclass, field
from datetime import date, datetime

from aitrader.agents.risk.portfolio import Holding, PortfolioSnapshot
from aitrader.agents.risk.tokens import TokenError, TokenSigner
from aitrader.backtest.engine import DecisionContext
from aitrader.backtest.models import Intent, OrderIntent
from aitrader.orchestrator.cycle import CycleResult, DecisionCycle
from aitrader.schemas.base import Instrument
from aitrader.schemas.orders import InstructionIntent, OrderInstruction
from aitrader.state.interface import StateStore


@dataclass
class ExecutedInstruction:
    day: date
    instruction: OrderInstruction
    accepted: bool
    reason: str | None = None


@dataclass
class ChainStrategy:
    cycle: DecisionCycle
    signer: TokenSigner
    state: StateStore
    instruments: Sequence[Instrument] = ()
    universe_index: str | None = None
    sectors: dict[str, str] = field(default_factory=dict)
    name: str = "agent_chain"
    log: list[ExecutedInstruction] = field(default_factory=list)
    cycles: list[CycleResult] = field(default_factory=list)
    _peak: float = 0.0
    _last_equity: float | None = None

    @property
    def params(self) -> dict[str, float | int | str | bool]:
        return {"universe": self.universe_index or ",".join(sorted(i.symbol for i in self.instruments))}

    def _universe(self, ctx: DecisionContext) -> list[Instrument]:
        if self.universe_index is None:
            return list(self.instruments)
        from aitrader.backtest.strategies import equity

        return [equity(s) for s in ctx.view.members(self.universe_index)]

    def snapshot(self, ctx: DecisionContext) -> PortfolioSnapshot:
        holdings = []
        for key, pos in sorted(ctx.positions.items()):
            inst = ctx.instruments[key]
            bars = ctx.view.bars(inst, 1)
            price = float(bars[-1].close) if bars else pos.avg_price
            holdings.append(
                Holding(
                    instrument=inst, quantity=pos.quantity, price=price, sector=self.sectors.get(inst.symbol)
                )
            )
        self._peak = max(self._peak, ctx.equity)
        start = self._last_equity if self._last_equity is not None else ctx.equity
        self._last_equity = ctx.equity
        return PortfolioSnapshot(
            as_of=ctx.as_of,
            equity=ctx.equity,
            cash=ctx.cash,
            day_start_equity=start,
            peak_equity=self._peak,
            holdings=tuple(holdings),
        )

    def decide(self, ctx: DecisionContext) -> list[OrderIntent]:
        instruments = self._universe(ctx)
        held = [ctx.instruments[k] for k in ctx.positions]
        universe = list({i.key: i for i in [*instruments, *held]}.values())
        result = asyncio.run(self.cycle.run(ctx.as_of, universe, self.snapshot(ctx)))
        self.cycles.append(result)
        intents = []
        for instruction in result.instructions:
            try:
                self.signer.verify(instruction, ctx.as_of, self.state)
            except TokenError as exc:
                self.log.append(ExecutedInstruction(ctx.day, instruction, False, str(exc)))
                continue
            self.log.append(ExecutedInstruction(ctx.day, instruction, True))
            intents.append(to_intent(instruction))
        return intents


def to_intent(instruction: OrderInstruction) -> OrderIntent:
    opening = instruction.intent in (InstructionIntent.OPEN, InstructionIntent.HEDGE)
    return OrderIntent(
        instrument=instruction.instrument,
        side=instruction.side.value,
        quantity=instruction.quantity,
        intent=Intent.OPEN if opening else Intent.CLOSE,
        stop=float(instruction.protective_stop) if opening and instruction.protective_stop else None,
        target=float(instruction.targets[0]) if opening and instruction.targets else None,
        limit_price=float(instruction.limit_price) if instruction.limit_price else None,
        tag=f"{instruction.action.value}:{instruction.instruction_id}",
    )


def run_cycle(
    cycle: DecisionCycle, as_of: datetime, instruments: Sequence[Instrument], portfolio: PortfolioSnapshot
) -> CycleResult:
    return asyncio.run(cycle.run(as_of, instruments, portfolio))
