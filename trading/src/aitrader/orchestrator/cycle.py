"""One decision cycle, end to end.

1. Analysts run under per-agent timeouts. A failure or timeout is recorded and the cycle
   continues without that agent; limits tighten for every missing agent (and more for a
   missing trend agent).
2. Candidates are held positions plus instruments with a chart setup.
3. For each candidate (in a fixed order), in sequence:
   debate (if available) -> boss -> liquidity -> R:R (opening trades) -> risk.
   A failed gate returns the decision to the boss, which may revise it (at most
   ``max_revisions`` times) or wait. Nothing moves forward past a failed gate.
4. Every output is published on the bus with causation links, so any order traces back
   through its approval, R:R verdict, liquidity plan and boss memo to the analyst messages.

The cycle id is derived from the decision time, so a replayed cycle reproduces its ids.
"""

from __future__ import annotations

import concurrent.futures
import time as time_mod
from collections.abc import Callable, Sequence
from dataclasses import dataclass, field
from datetime import datetime, timedelta
from functools import partial
from typing import Any, TypeVar
from uuid import UUID, uuid5

from aitrader.agents.boss.agent import BossAgent
from aitrader.agents.boss.fallback import fallback_decision
from aitrader.agents.boss.inputs import BossInput
from aitrader.agents.chart.agent import ChartAgent
from aitrader.agents.debate.agent import DebateAgent
from aitrader.agents.liquidity.agent import LiquidityAgent
from aitrader.agents.news.agent import NewsAgent
from aitrader.agents.news.pipeline import RawDocument
from aitrader.agents.publishing import publish
from aitrader.agents.risk.agent import RiskAgent, limit_multiplier
from aitrader.agents.risk.portfolio import PortfolioSnapshot
from aitrader.agents.risk_reward.agent import RiskRewardAgent
from aitrader.agents.smart_money.engine import SmartMoneyEngine
from aitrader.agents.trend.agent import TrendAgent
from aitrader.bus.interface import MessageBus
from aitrader.config.settings import RiskLimits
from aitrader.schemas.base import AgentId, EvidenceKind, EvidenceRef, Instrument, Priority
from aitrader.schemas.canonical import digest
from aitrader.schemas.decision import Action, BossDecision
from aitrader.schemas.envelope import Envelope, Topic
from aitrader.schemas.feedback import AgentHeartbeat, HealthStatus
from aitrader.schemas.news import EventRiskFlag, NewsEvent
from aitrader.schemas.orders import OrderInstruction
from aitrader.schemas.regime import RegimeLabel, RegimeOutlook
from aitrader.schemas.signals import ChartSignal, SetupType, SmartMoneySignal
from aitrader.state.interface import StateStore
from aitrader.tools.sizing import fixed_fractional

T = TypeVar("T")
_CYCLE_NS = UUID("0f6b0b52-4b56-4b2e-9d7c-1e0f7a6c3d21")


@dataclass(frozen=True)
class CycleTimeouts:
    analyst: float = 120.0
    debate: float = 180.0
    boss: float = 120.0


@dataclass
class AgentStatus:
    agent_id: AgentId
    status: HealthStatus
    latency_ms: float
    error: str | None = None


@dataclass
class CycleResult:
    cycle_id: UUID
    as_of: datetime
    instructions: list[OrderInstruction] = field(default_factory=list)
    decisions: list[BossDecision] = field(default_factory=list)
    statuses: dict[AgentId, AgentStatus] = field(default_factory=dict)
    envelopes: list[Envelope] = field(default_factory=list)
    notes: list[str] = field(default_factory=list)
    limit_multiplier: float = 1.0


def _trend_outputs(trend: TrendAgent, as_of: datetime) -> tuple[RegimeOutlook | None, list[EventRiskFlag]]:
    return trend.outlook(as_of)[0], trend.event_flags(as_of)


def guarded(fn: Callable[[], T], timeout: float) -> tuple[T | None, HealthStatus, float, str | None]:
    """Run ``fn`` with a wall-clock timeout; never raises."""
    start = time_mod.perf_counter()
    pool = concurrent.futures.ThreadPoolExecutor(max_workers=1)
    future = pool.submit(fn)
    try:
        value = future.result(timeout=timeout)
        status, error = HealthStatus.HEALTHY, None
    except concurrent.futures.TimeoutError:
        value, status, error = None, HealthStatus.TIMED_OUT, f"timed out after {timeout}s"
    except Exception as exc:  # an agent failure must never stop the cycle
        value, status, error = None, HealthStatus.FAILED, f"{type(exc).__name__}: {exc}"
    finally:
        pool.shutdown(wait=False, cancel_futures=True)
    return value, status, (time_mod.perf_counter() - start) * 1000, error


class DecisionCycle:
    def __init__(
        self,
        *,
        bus: MessageBus,
        state: StateStore,
        limits: RiskLimits,
        chart: ChartAgent,
        smart_money: SmartMoneyEngine | None,
        trend: TrendAgent | None,
        news: NewsAgent | None,
        debate: DebateAgent,
        boss: BossAgent,
        liquidity: LiquidityAgent,
        risk_reward: RiskRewardAgent,
        risk: RiskAgent,
        timeouts: CycleTimeouts | None = None,
        max_revisions: int = 2,
        sectors: dict[str, str] | None = None,
        credibility: dict[AgentId, float] | None = None,
    ) -> None:
        self.bus = bus
        self.state = state
        self.limits = limits
        self.chart = chart
        self.smart_money = smart_money
        self.trend = trend
        self.news = news
        self.debate = debate
        self.boss = boss
        self.liquidity = liquidity
        self.rr = risk_reward
        self.risk = risk
        self.timeouts = timeouts or CycleTimeouts()
        self.max_revisions = max_revisions
        self.sectors = sectors or {}
        self.credibility = credibility or {}

    async def run(
        self,
        as_of: datetime,
        instruments: Sequence[Instrument],
        portfolio: PortfolioSnapshot,
        documents: Sequence[RawDocument] = (),
        human_approved: frozenset[str] = frozenset(),
    ) -> CycleResult:
        cycle_id = uuid5(_CYCLE_NS, as_of.isoformat())
        result = CycleResult(cycle_id=cycle_id, as_of=as_of)
        produced = as_of + timedelta(seconds=1)

        async def emit(
            topic: Topic, agent: AgentId, payload: Any, causes: tuple[UUID, ...] = (), **kw: Any
        ) -> Envelope:
            # Deterministic ids: a replayed cycle reproduces every message and every reference.
            message_id = uuid5(cycle_id, f"{topic.value}|{agent.value}|{digest(payload)}")
            env = await publish(
                self.bus,
                message_id=message_id,
                topic=topic,
                agent_id=agent,
                payload=payload,
                available_at=kw.pop("available_at", as_of),
                produced_at=produced,
                cycle_id=cycle_id,
                causation_ids=causes,
                **kw,
            )
            result.envelopes.append(env)
            return env

        def record(agent: AgentId, status: HealthStatus, latency: float, error: str | None) -> None:
            previous = result.statuses.get(agent)
            if previous is None or previous.status is HealthStatus.HEALTHY:
                result.statuses[agent] = AgentStatus(agent, status, latency, error)

        # ---------------------------------------------------------- analysts
        outlook: RegimeOutlook | None = None
        flags: list[EventRiskFlag] = []
        if self.trend is not None:
            trend = self.trend
            trend_value, status, ms, err = guarded(
                partial(_trend_outputs, trend, as_of), self.timeouts.analyst
            )
            record(AgentId.TREND, status, ms, err)
            if trend_value is not None:
                outlook, macro = trend_value
                flags.extend(macro)
        events: list[NewsEvent] = []
        if self.news is not None and documents:
            news = self.news
            news_value, status, ms, err = guarded(
                partial(news.process, documents, as_of, cycle_id), self.timeouts.analyst
            )
            record(AgentId.NEWS, status, ms, err)
            if news_value is not None:
                events, flags = news_value.events, flags + news_value.flags
        charts: dict[str, ChartSignal] = {}
        smart: dict[str, SmartMoneySignal] = {}
        for inst in sorted(instruments, key=lambda i: i.key):
            value_c, status, ms, err = guarded(
                partial(self.chart.analyse, inst, as_of), self.timeouts.analyst
            )
            record(AgentId.CHART, status, ms, err)
            if value_c is not None:
                charts[inst.key] = value_c
            if self.smart_money is not None:
                engine = self.smart_money
                value_s, status, ms, err = guarded(
                    partial(engine.stock_signal, inst, as_of), self.timeouts.analyst
                )
                record(AgentId.SMART_MONEY, status, ms, err)
                if value_s is not None:
                    smart[inst.key] = value_s

        # Publish analyst outputs; remember message ids for causation and the debate.
        regime_msg = await emit(Topic.REGIME_OUTLOOK, AgentId.TREND, outlook) if outlook else None
        flag_msgs = [
            await emit(
                Topic.NEWS_RISK_FLAGS, AgentId.TREND if f.flag_id.startswith("macro-") else AgentId.NEWS, f
            )
            for f in flags
        ]
        event_msgs = {
            e.event_id: await emit(Topic.NEWS_EVENTS, AgentId.NEWS, e, event_time=e.first_published_at)
            for e in events
        }
        chart_msgs = {k: await emit(Topic.SIGNALS_CHART, AgentId.CHART, s) for k, s in charts.items()}
        smart_msgs = {
            k: await emit(Topic.SIGNALS_SMARTMONEY, AgentId.SMART_MONEY, s) for k, s in smart.items()
        }

        expected = [
            a
            for a, present in (
                (AgentId.TREND, self.trend),
                (AgentId.CHART, True),
                (AgentId.SMART_MONEY, self.smart_money),
                (AgentId.NEWS, self.news and documents),
            )
            if present
        ]
        failed = [
            a
            for a in expected
            if result.statuses.get(a) and result.statuses[a].status is not HealthStatus.HEALTHY
        ]
        live = len(expected) - len(failed)
        trend_missing = self.trend is None or AgentId.TREND in failed
        multiplier = limit_multiplier(
            self.limits,
            missing_analysts=len(failed),
            trend_missing=trend_missing,
            exposure_multiplier=outlook.exposure_multiplier if outlook else 1.0,
            portfolio=portfolio,
        )
        result.limit_multiplier = multiplier
        self.state.set_limit_multiplier(multiplier)
        self.state.set_regime(outlook.regime if outlook else RegimeLabel.UNKNOWN)
        for alert in self.risk.drawdown_alerts(portfolio, as_of):
            await emit(Topic.RISK_ALERTS, AgentId.RISK, alert, priority=Priority.INTERRUPT)

        # ---------------------------------------------------------- per candidate
        held = {h.instrument.key: h for h in portfolio.holdings}
        candidates = sorted(
            {
                i.key: i
                for i in instruments
                if i.key in held or (i.key in charts and charts[i.key].setup is not SetupType.NONE)
            }.values(),
            key=lambda i: i.key,
        )
        for h in portfolio.holdings:
            if h.instrument.key not in {c.key for c in candidates}:
                candidates.append(h.instrument)
        for inst in candidates:
            key = inst.key
            chart = charts.get(key)
            causes = tuple(
                m.message_id
                for m in (chart_msgs.get(key), smart_msgs.get(key), regime_msg, *flag_msgs)
                if m is not None
            )
            message_ids = {
                a: m.message_id
                for a, m in (
                    ("chart", chart_msgs.get(key)),
                    ("smart_money", smart_msgs.get(key)),
                    ("trend", regime_msg),
                )
                if m is not None
            }
            inst_events = [e for e in events if any(i.key == key for i in e.instruments)]
            evidence = {
                f"{name}:{m.message_id}": (
                    EvidenceRef(kind=EvidenceKind.MESSAGE, ref_id=str(m.message_id), available_at=as_of),
                    summary,
                )
                for name, m, summary in (
                    (
                        "chart",
                        chart_msgs.get(key),
                        f"chart {chart.setup.value}, score {chart.score:+.2f}" if chart else "",
                    ),
                    (
                        "smart_money",
                        smart_msgs.get(key),
                        "; ".join(smart[key].components) if key in smart else "",
                    ),
                    ("regime", regime_msg, f"regime {outlook.regime.value}" if outlook else ""),
                )
                if m is not None
            } | {
                f"news:{event_msgs[e.event_id].message_id}": (
                    EvidenceRef(
                        kind=EvidenceKind.MESSAGE,
                        ref_id=str(event_msgs[e.event_id].message_id),
                        available_at=as_of,
                    ),
                    f"{e.event_type.value}: {e.claims[0].summary}",
                )
                for e in inst_events
            }
            debate_out = None
            if self.debate.available:
                debate_out, status, ms, err = guarded(
                    partial(self.debate.run, inst, evidence, cycle_id), self.timeouts.debate
                )
                record(AgentId.DEBATE, status, ms, err)
                if debate_out is not None:
                    causes += (
                        (await emit(Topic.DEBATE_OUTPUT, AgentId.DEBATE, debate_out, causes)).message_id,
                    )
            boss_input = BossInput(
                instrument=inst,
                as_of=as_of,
                chart=chart,
                smart_money=smart.get(key),
                news=inst_events,
                regime=outlook,
                flags=[f for f in flags if f.instrument is None or f.instrument.key == key],
                holding=held.get(key),
                debate=debate_out,
                credibility=self.credibility,
                message_ids=message_ids,
            )
            decision_id = uuid5(cycle_id, key)
            decision, status, ms, err = guarded(
                partial(self.boss.decide, boss_input, decision_id, cycle_id), self.timeouts.boss
            )
            record(AgentId.BOSS, status, ms, err)
            if decision is None:
                decision = fallback_decision(boss_input, decision_id)
                result.notes.append(f"{key}: boss {status.value}; deterministic fallback used")
            await self._gate_chain(
                decision,
                boss_input,
                chart,
                portfolio,
                flags,
                live,
                cycle_id,
                as_of,
                causes,
                human_approved,
                result,
                emit,
            )

        for agent, st in sorted(result.statuses.items()):
            await emit(
                Topic.SYSTEM_HEARTBEAT,
                AgentId.ORCHESTRATOR,
                AgentHeartbeat(
                    agent_id=agent,
                    status=st.status,
                    last_output_at=as_of if st.status is HealthStatus.HEALTHY else None,
                    latency_ms=st.latency_ms,
                ),
            )
        return result

    async def _gate_chain(
        self,
        decision: BossDecision,
        boss_input: BossInput,
        chart: ChartSignal | None,
        portfolio: PortfolioSnapshot,
        flags: list[EventRiskFlag],
        live: int,
        cycle_id: UUID,
        as_of: datetime,
        causes: tuple[UUID, ...],
        human_approved: frozenset[str],
        result: CycleResult,
        emit: Callable[..., Any],
    ) -> None:
        inst = decision.instrument
        for _ in range(self.max_revisions + 1):
            result.decisions.append(decision)
            decision_msg = await emit(
                Topic.BOSS_DECISION,
                AgentId.BOSS_FALLBACK if decision.used_fallback else AgentId.BOSS,
                decision,
                causes,
            )
            if decision.action in (Action.HOLD, Action.WAIT):
                return
            opening = decision.action is Action.BUY or (
                decision.action is Action.SELL and decision.open_short
            )
            plan = verdict = None
            chain: tuple[UUID, ...] = (decision_msg.message_id,)
            rejection: str | None = None
            if opening:
                quantity = self._provisional_quantity(decision, chart, portfolio)
                plan = self.liquidity.plan(decision.decision_id, inst, quantity, as_of)
                if plan is None:
                    rejection = "no liquidity data"
                else:
                    chain += ((await emit(Topic.LIQUIDITY_PLAN, AgentId.LIQUIDITY, plan, chain)).message_id,)
                    verdict = self.rr.evaluate(decision, chart, plan, quantity, flags, as_of)
                    chain += ((await emit(Topic.RR_VERDICT, AgentId.RISK_REWARD, verdict, chain)).message_id,)
                    if not verdict.passed:
                        rejection = "R:R gate: " + ", ".join(r.value for r in verdict.rejections)
            if rejection is None:
                outcome = self.risk.assess(
                    decision,
                    chart=chart,
                    plan=plan,
                    verdict=verdict,
                    portfolio=portfolio,
                    flags=flags,
                    live_analysts=live,
                    cycle_id=cycle_id,
                    as_of=as_of,
                    human_approved=inst.key in human_approved,
                    sector=self.sectors.get(inst.symbol),
                )
                for alert in outcome.alerts:
                    await emit(Topic.RISK_ALERTS, AgentId.RISK, alert, priority=Priority.INTERRUPT)
                approval_msg = await emit(Topic.RISK_APPROVAL, AgentId.RISK, outcome.approval, chain)
                if outcome.instruction is not None:
                    await emit(
                        Topic.ORDERS_INSTRUCTIONS,
                        AgentId.RISK,
                        outcome.instruction,
                        (*chain, approval_msg.message_id),
                    )
                    result.instructions.append(outcome.instruction)
                    return
                rejection = "risk veto: " + ", ".join(
                    r.rule.value for r in outcome.approval.results if not r.passed
                )
            result.notes.append(f"{inst.key}: {rejection}")
            if decision.revision_number >= self.max_revisions:
                return
            decision = self.boss.revise(boss_input, decision, rejection, cycle_id)

    def _provisional_quantity(
        self, decision: BossDecision, chart: ChartSignal | None, portfolio: PortfolioSnapshot
    ) -> int:
        """The size the risk agent is likely to approve, so liquidity and costs are assessed
        at a realistic size. The risk agent recomputes it independently."""
        if (
            chart is None
            or chart.structural_stop is None
            or chart.entry_low is None
            or chart.entry_high is None
        ):
            return decision.instrument.lot_size
        entry = (float(chart.entry_low) + float(chart.entry_high)) / 2
        per_trade = self.limits.per_trade
        risk_fraction = (
            per_trade.max_risk_fraction * decision.risk_budget_fraction * self.state.limit_multiplier()
        )
        if risk_fraction <= 0 or entry == float(chart.structural_stop):
            return decision.instrument.lot_size
        size = fixed_fractional(
            equity=portfolio.equity,
            risk_fraction=min(risk_fraction, 0.02),
            entry=entry,
            stop=float(chart.structural_stop),
            lot_size=decision.instrument.lot_size,
            max_position_value=per_trade.max_position_fraction * portfolio.equity,
        )
        return max(size.quantity, decision.instrument.lot_size)
